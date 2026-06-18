(ns metabase.metabot.self.custom
  "Custom OpenAI-compatible provider adapter for Metabot streaming.

  Unlike the official OpenAI/Anthropic adapters we intentionally do NOT pipe raw
  provider chunks through the shared [[metabase.metabot.self.core/aisdk-xf]]
  aggregator.  SiliconFlow-style OpenAI-compatible providers split tool-call
  arguments across dozens of tiny chunks, omit `tool_call.id` on subsequent deltas,
  and interleave non-empty `:usage` chunks between tool deltas.  Relying on the
  generic aggregator means fighting those behaviours in upstream code.

  This namespace therefore:

  1. Builds a standard `/v1/chat/completions` request.
  2. Streams the SSE response itself.
  3. Accumulates text and tool-call arguments locally.
  4. Emits AI SDK v5-style chunks ONLY when a logical unit is complete:
     - `:text-start` + `:text-delta` chunks while streaming normal assistant text.
       (Upstream aisdk-xf does not yet understand :text-end, so we deliberately omit it.)
     - A single `:tool-input-start` (with complete arguments) followed by a
       `:tool-input-available` when the model signals `finish_reason=tool_calls`.
     - A single `:usage` chunk at the very end of the stream.

  Because the output is already well-formed from the perspective of the generic
  transducers, the shared `aisdk-xf`/`lite-aisdk-xf` stages can remain untouched,
  which makes upstream rebases much safer."
  (:require
   [clojure.string :as str]
   [malli.json-schema :as mjs]
   [metabase.llm.settings :as llm]
   [metabase.metabot.self.core :as core]
   [metabase.metabot.self.schema :as schema]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.json :as json]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

(def ^:private ^String custom-provider-marker
  "Verification marker logged when the custom provider adapter is chosen."
  "METABOT_CUSTOM_PROVIDER_V2")

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
                                         tool_choice tool_choice
                                         :else       "auto"))
      temperature  (assoc :temperature temperature)
      max-tokens   (assoc :max_tokens max-tokens)
      (and schema (not all-tools))
      (assoc :response_format {:type        "json_schema"
                               :json_schema {:name "structured_output"
                                             :schema schema}}))))

;;; ---------------------------------------------------------------------------
;;; Provider-agnostic streaming normalisation

;; Chunk types we emit.  These are AI SDK v5 style *chunks* meant to be consumed
;; by [[core/aisdk-xf]] / [[core/lite-aisdk-xf]] / [[core/tool-executor-xf]].

(defn- chat-completions->aisdk-chunks-xf
  "Transducer that turns a raw OpenAI-compatible `/v1/chat/completions` SSE stream
  into AI SDK v5 chunks, hiding provider-specific fragmentation.

  Important invariants:
  - Tool-call argument fragments are accumulated locally.  We emit exactly one
    `:tool-input-start` chunk (with complete arguments) followed by one
    `:tool-input-available` chunk when `finish_reason` becomes `tool_calls`.
  - We never emit `:tool-input-delta` chunks.  That is the chunk type that the
    generic aggregator keys by `toolCallId`, and because providers omit the id on
    subsequent fragments it breaks aggregation.
  - Non-final `:usage` chunks are ignored; we emit a single `:usage` chunk at the
    end of the stream.  This prevents a mid-stream usage chunk from flushing an
    as-yet-incomplete tool or text group in the shared transducers."
  []
  (fn [rf]
    (let [text-id     (atom nil)
          text-began? (atom false)
          tool-id     (atom nil)
          tool-name   (atom nil)
          tool-args   (atom "")
          last-usage  (atom nil)
          last-role   (atom nil)]
      (fn
        ([result]
         ;; Flush any collected tool call even if the finish_reason wasn't seen
         ;; (defensive; normally finish_reason=tool_calls is emitted).
         (let [r1 (if-let [tid @tool-id]
                    (let [tname @tool-name
                          targs @tool-args]
                      (reset! tool-id nil)
                      (reset! tool-name nil)
                      (reset! tool-args "")
                      (-> result
                          (rf {:type           :tool-input-start
                               :toolCallId     tid
                               :toolName       tname
                               :inputTextDelta targs})
                          (rf {:type           :tool-input-available
                               :toolCallId     tid
                               :toolName       tname})))
                    result)
               r2 (if-let [usage @last-usage]
                    (do (reset! last-usage nil)
                        (rf r1 {:type  :usage
                                :usage {:promptTokens     (or (:prompt_tokens usage) 0)
                                        :completionTokens (or (:completion_tokens usage) 0)}}))
                    r1)]
           (rf r2)))

        ([result chunk]
         (let [choices    (:choices chunk)
               choice     (first choices)
               delta      (:delta choice)
               finish     (:finish_reason choice)
               id         (:id chunk)
               usage      (:usage chunk)
               role       (or (:role delta) @last-role)
               content    (:content delta)
               tool-delta (:tool_calls delta)
               parts-acc  (atom [])]
           (when role
             (reset! last-role role))

           ;; ------------------------------------------------------------------
           ;; plain assistant text
           (when (and content (not (str/blank? content)))
             (when-not @text-id
               (reset! text-id id)
               (swap! parts-acc conj {:type :text-start :id id}))
             (when-not @text-began?
               (reset! text-began? true))
             (swap! parts-acc conj {:type  :text-delta
                                    :id    @text-id
                                    :delta content}))

           ;; ------------------------------------------------------------------
           ;; tool-call fragment accumulation (kept private to this transducer)
           (doseq [tc tool-delta
                   :let [idx (:index tc 0)
                         ;; Some providers return a single-element vector; we only
                         ;; support one tool call at a time for structured output.
                         _ (when-not (zero? idx)
                             (log/warn "Custom provider returned non-zero tool_call index"
                                       {:index idx :chunk chunk}))]]
             (let [tid   (or (:id tc) @tool-id (core/mkid))
                   tname (get-in tc [:function :name])
                   args  (get-in tc [:function :arguments])]
               (when-not @tool-id
                 (reset! tool-id tid))
               (when (seq tname)
                 (reset! tool-name tname))
               (when (seq args)
                 (swap! tool-args str args))))

           ;; ------------------------------------------------------------------
           ;; finish_reason handling
           (condp = finish
            "stop"
            nil

             "tool_calls"
             (when-let [tid @tool-id]
               (let [tname @tool-name
                     targs @tool-args]
                 (reset! tool-id nil)
                 (reset! tool-name nil)
                 (reset! tool-args "")
                 (swap! parts-acc conj {:type           :tool-input-start
                                        :toolCallId     tid
                                        :toolName       tname
                                        :inputTextDelta targs})
                 (swap! parts-acc conj {:type           :tool-input-available
                                        :toolCallId     tid
                                        :toolName       tname})))

             nil)

           ;; ------------------------------------------------------------------
           ;; usage: stash only the latest, emit at completion.  We deliberately
           ;; ignore incremental usage chunks because they cause the upstream
           ;; aggregator to flush mid-stream.
           (when usage
             (reset! last-usage usage))

           (reduce rf result @parts-acc)))))))

(defn custom-raw
  "Perform a streaming request to a custom OpenAI-compatible /v1/chat/completions endpoint."
  ([opts]
   (log/info custom-provider-marker "Custom provider streaming request starting")
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
