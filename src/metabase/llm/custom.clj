(ns metabase.llm.custom
  "Custom OpenAI-compatible provider client for OSS LLM integration."
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.llm.settings :as llm.settings]
   [metabase.util :as u]
   [metabase.util.json :as json]))

(set! *warn-on-reflection* true)

(def ^:private generate-sql-tool
  "Tool definition for structured SQL output (OpenAI format)."
  {:type "function"
   :function
   {:name "generate_sql"
    :description "Generate SQL query from the user's request. Always use this tool to return your response."
    :parameters
    {:type "object"
     :properties
     {:sql {:type "string" :description "The generated SQL query"}
      :explanation {:type "string" :description "Brief explanation of the query"}}
     :required ["sql"]}}})

(defn chat-completion
  "Send a chat completion request to a custom OpenAI-compatible provider.
   Returns a map with:
   - :result      - Map with :sql and optionally :explanation from the tool response
   - :usage       - Map with :model, :prompt (input tokens), :completion (output tokens)
   - :duration-ms - Request duration in milliseconds

   Options:
   - :model    - Model to use (default: configured custom model)
   - :system   - System prompt
   - :messages - Vector of {:role :content} maps for conversation history"
  [{:keys [model system messages]}]
  (let [base-url  (llm.settings/llm-custom-provider-base-url)
        api-key   (llm.settings/llm-custom-provider-api-key)
        model     (or model (llm.settings/llm-custom-provider-model))
        start-time (u/start-timer)]
    (when (str/blank? api-key)
      (throw (ex-info "Custom LLM provider API key is not configured."
                      {:type :llm-not-configured})))
    (when (str/blank? base-url)
      (throw (ex-info "Custom LLM provider base URL is not configured."
                      {:type :llm-not-configured})))
    (let [url (str (str/replace base-url #"/+$" "") "/chat/completions")
          body {:model model
                :messages (concat
                           (when system [{:role "system" :content system}])
                           messages)
                :tools [generate-sql-tool]
                :tool_choice {:type "function" :function {:name "generate_sql"}}
                :max_tokens (llm.settings/llm-max-tokens)}
          response (http/post url
                              {:headers {"Authorization" (str "Bearer " api-key)
                                         "Content-Type" "application/json"}
                               :body (json/encode body)
                               :as :json
                               :content-type :json
                               :socket-timeout (llm.settings/llm-request-timeout-ms)
                               :connection-timeout (llm.settings/llm-connection-timeout-ms)})
          duration-ms (u/since-ms start-time)
          body-resp (:body response)
          choice (first (:choices body-resp))
          message (:message choice)
          tool-calls (:tool_calls message)
          tool-call (first tool-calls)
          args (when tool-call
                 (json/decode (:arguments (:function tool-call))))]
      {:result (or args {:sql ""})
       :duration-ms duration-ms
       :usage {:model model
               :prompt (get-in body-resp [:usage :prompt_tokens] 0)
               :completion (get-in body-resp [:usage :completion_tokens] 0)}})))
