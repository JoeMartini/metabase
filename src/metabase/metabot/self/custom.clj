(ns metabase.metabot.self.custom
  "Custom OpenAI-compatible provider adapter for Metabot streaming.

  Uses the standard /v1/chat/completions endpoint (with tool_choice for
  structured/tool calls) so it works with OpenAI-compatible providers such as
  SiliconFlow that do not implement the newer /v1/responses API."
  (:require
   [clojure.string :as str]
   [malli.json-schema :as mjs]
   [metabase.llm.settings :as llm]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.schema :as schema]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]))

(set! *warn-on-reflection* true)

(defn- tool->custom
  "Convert a tool definition map to OpenAI Chat Completions function format."
  [{:keys [tool-name doc schema]}]
  (let [[_:=> [_:cat params] _out] schema
        params                       (schema/filter-schema-by-features params)
        doc                          (if (str/starts-with? (or doc "") "Inputs: ")
                                       (second (str/split doc #"\n\n  " 2))
                                       doc)]
    {:type     "function"
     :function {:name        tool-name
                :description doc
                :parameters  (mjs/transform params {:additionalProperties false})}}))

(defn- parts->messages
  "Convert AISDK parts/user-messages into Chat Completions message format."
  [system-msg input]
  (cond-> []
    (not-empty system-msg)
    (conj {:role "system" :content system-msg})

    :always
    (into (mapv (fn [part]
                  (case (:type part)
                    :text        {:role "assistant" :content (:text part)}
                    :tool-input  {:role "assistant"
                                  :content nil
                                  :tool_calls [{:id (or (:id part) (core/mkid))
                                                :type "function"
                                                :function {:name (:function part)
                                                           :arguments (json/encode (:arguments part))}}]}
                    :tool-output {:role "tool"
                                  :tool_call_id (core/mkid)
                                  :content (or (get-in part [:result :output])
                                               "")}
                    {:role    (name (or (:role part) "user"))
                     :content (or (:content part) "")}))
                input))))

(defn- custom-error-msg
  [res]
  (let [status (long (:status res 0))]
    (case status
      401 (tru "Custom LLM API key expired or invalid")
      403 (tru "Custom LLM API key has insufficient permissions")
      404 (tru "Custom LLM endpoint or model listing is unavailable")
      429 (tru "Custom LLM provider has rate limited us")
      500 (tru "Custom LLM provider is not working")
      (tru "Custom LLM provider error (HTTP {0})" status))))

(defn- normalize-base-url
  "Custom users often paste the OpenAI-style URL including /v1. The rest of this
  adapter uses the same convention as the official OpenAI adapter (base URL without
  trailing /v1), so strip it here."
  [url]
  (-> url
      (str/replace #"/*$" "")
      (str/replace #"/v1$" "")))

(defn list-models
  "List available models from a custom OpenAI-compatible provider."
  ([] (list-models {}))
  ([{:keys [credentials ai-proxy?]}]
   (let [api-key  (or (and credentials (not-empty (:api-key credentials)))
                      (llm/llm-custom-provider-api-key))
         base-url (-> (or (and credentials (not-empty (:base-url credentials)))
                          (llm/llm-custom-provider-base-url))
                      normalize-base-url)]
     (when (str/blank? api-key)
       (throw (core/missing-api-key-ex "Custom")))
     (try
       (let [auth (core/resolve-auth "custom" "Custom"
                                     (when api-key
                                       {:url     base-url
                                        :headers {"Authorization" (str "Bearer " api-key)}})
                                     ai-proxy?)
             res  (core/request auth {:method  :get
                                      :url     "/v1/models"
                                      :as      :json
                                      :headers {"Content-Type" "application/json"}})]
         {:models (mapv (fn [model]
                          {:id           (:id model)
                           :display_name (:id model)})
                        (reverse (sort-by :created (get-in res [:body :data]))))})
       (catch Exception e
         (core/rethrow-api-error! "custom" custom-error-msg e))))))

(defn- chat-completions-request-body
  "Build the Chat Completions request body for a custom provider."
  [{:keys [model system input tools schema tool_choice temperature max-tokens]
    :or   {model "deepseek-ai/DeepSeek-V3"}}]
  (let [messages  (parts->messages system input)
        all-tools (or (when schema
                        [{:type     "function"
                          :function {:name        "structured_output"
                                     :description "Output structured data"
                                     :parameters  schema}}])
                      (when (seq tools) (mapv tool->custom tools)))
        body      {:model    model
                   :stream   true
                   :messages messages}]
    (cond-> body
      all-tools    (assoc :tools all-tools
                          :tool_choice (cond
                                         schema      {"type" "function"
                                                      "function" {"name" "structured_output"}}
                                         tool_choice tool_choice
                                         :else       "auto"))
      temperature  (assoc :temperature temperature)
      max-tokens   (assoc :max_tokens max-tokens)
      (and schema (not all-tools))
      (assoc :response_format {:type        "json_schema"
                               :json_schema {:name "structured_output"
                                             :schema schema}}))))

(defn- chat-completions->aisdk-chunks-xf
  "Transducer that turns OpenAI /v1/chat/completions SSE chunks into AISDK v5 parts."
  []
  (fn [rf]
    (let [tool-id      (atom nil)
          tool-name    (atom nil)
          tool-args    (atom "")
          text-id      (atom nil)
          role         (atom nil)]
      (fn
        ([result]
         (let [r1 (if-let [tid @tool-id]
                    (let [tname @tool-name
                          targs @tool-args]
                      (reset! tool-id nil)
                      (reset! tool-name nil)
                      (reset! tool-args "")
                      (rf result {:type           :tool-input-start
                                  :toolCallId     tid
                                  :toolName       tname
                                  :inputTextDelta targs}))
                    result)
               r2 (if-let [tid @text-id]
                    (do (reset! text-id nil)
                        (rf r1 {:type :text-end :id tid}))
                    r1)]
           (rf r2)))
        ([result chunk]
         (let [choices  (:choices chunk)
               choice   (first choices)
               delta    (:delta choice)
               finish   (:finish_reason choice)
               id       (:id chunk)
               usage    (:usage chunk)
               new-role (or (:role delta) @role)
               parts    (atom [])]
           (reset! role new-role)
           ;; text start for plain assistant responses
           (when (and (= new-role "assistant") (nil? @text-id) (nil? @tool-id) (:content delta))
             (reset! text-id id)
             (swap! parts conj {:type :start :messageId id})
             (swap! parts conj {:type :text-start :id id}))

           ;; text content deltas
           (when (and delta (:content delta))
             (when-not @text-id
               (reset! text-id id)
               (swap! parts conj {:type :text-start :id id}))
             (swap! parts conj {:type  :text-delta
                                :id    @text-id
                                :delta (:content delta)}))

           ;; tool_call deltas - accumulated and emitted as a single tool-input-start
           (doseq [tc (:tool_calls delta)]
             (let [tid (or (:id tc) @tool-id (core/mkid))]
               (when-not @tool-id
                 (reset! tool-id tid))
               (when (get-in tc [:function :name])
                 (reset! tool-name (get-in tc [:function :name])))
               (when-let [args (get-in tc [:function :arguments])]
                 (swap! tool-args str args))))

           ;; finish reasons
           (condp = finish
             "stop"
             (when-let [tid @text-id]
               (reset! text-id nil)
               (swap! parts conj {:type :text-end :id tid}))

             "tool_calls"
             (when-let [tid @tool-id]
               (let [tname @tool-name
                     targs @tool-args]
                 (reset! tool-id nil)
                 (reset! tool-name nil)
                 (reset! tool-args "")
                 (swap! parts conj {:type           :tool-input-start
                                    :toolCallId     tid
                                    :toolName       tname
                                    :inputTextDelta targs})))

             nil)

           ;; usage block at the end of the stream
           (when usage
             (swap! parts conj {:type  :usage
                                :usage {:promptTokens     (:prompt_tokens usage 0)
                                        :completionTokens (:completion_tokens usage 0)
                                        :model            (:model chunk)}}))

           (reduce rf result @parts)))))))

(defn custom-raw
  "Perform a streaming request to a custom OpenAI-compatible /v1/chat/completions endpoint."
  ([opts]
   (let [model (or (:model opts) "deepseek-ai/DeepSeek-V3")
         req   (chat-completions-request-body opts)]
     (try
       (let [api-key  (not-empty (or (:api-key opts) (llm/llm-custom-provider-api-key)))
             base-url (-> (or (:base-url opts) (llm/llm-custom-provider-base-url))
                          normalize-base-url)
             _        (when (str/blank? api-key)
                        (throw (core/missing-api-key-ex "Custom")))
             auth     (core/resolve-auth "custom" "Custom"
                                         {:url     base-url
                                          :headers {"Authorization" (str "Bearer " api-key)}}
                                         (:ai-proxy? opts))
             response (core/request auth
                                    {:method  :post
                                     :url     "/v1/chat/completions"
                                     :as      :stream
                                     :headers {"Content-Type" "application/json"}
                                     :body    (json/encode req)})]
         (eduction (chat-completions->aisdk-chunks-xf)
                   (core/sse-reducible (:body response))))
       (catch Exception e
         (core/rethrow-api-error! "custom" custom-error-msg e))))))

(defn custom
  "Call custom provider, return AISDK stream."
  [& args]
  (apply custom-raw args))
