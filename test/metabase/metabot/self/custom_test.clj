(ns metabase.metabot.self.custom-test
  "Unit tests for the custom OpenAI-compatible provider adapter.

  These tests focus on the streaming normalisation transducer and request body
  construction.  They do not hit the network; raw provider SSE chunks are fed
  directly into the private transducer as Clojure maps."
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.metabot.self.custom :as custom]))

(set! *warn-on-reflection* true)

(def ^:private xf #'custom/chat-completions->aisdk-chunks-xf)
(def ^:private req-body #'custom/chat-completions-request-body)
(def ^:private normalize-base-url #'custom/normalize-base-url)
(def ^:private parts->messages #'custom/parts->messages)

(defn- chunks->parts [chunks]
  (into [] (xf) chunks))

;;; ---------------------------------------------------------------------------
;;; Base URL normalisation

(deftest normalize-base-url-test
  (testing "strips /v1 suffix"
    (is (= "https://api.siliconflow.cn"
           (normalize-base-url "https://api.siliconflow.cn/v1")))
    (is (= "https://api.siliconflow.cn"
           (normalize-base-url "https://api.siliconflow.cn/v1/"))))
  (testing "strips only trailing slashes when no /v1"
    (is (= "https://example.com"
           (normalize-base-url "https://example.com/")))
    (is (= "https://example.com"
           (normalize-base-url "https://example.com")))))

;;; ---------------------------------------------------------------------------
;;; Request body construction

(deftest chat-completions-request-body-defaults-test
  (testing "model and stream flag are passed through"
    (let [body (req-body {:input [{:role :user :content "hi"}] :model "gpt-4o"})]
      (is (= "gpt-4o" (:model body)))
      (is (true? (:stream body)))
      (is (= [{:role "user" :content "hi"}] (:messages body)))
      (is (nil? (:tools body)))
      (is (nil? (:tool_choice body)))))

  (testing "schema-based structured output uses tools and auto tool_choice"
    (let [schema {:type "object" :properties {"x" {:type "string"}}}
          body   (req-body {:input [{:role :user :content "go"}]
                            :model "gpt-4o"
                            :schema schema})]
      (is (= "auto" (:tool_choice body)))
      (is (= 1 (count (:tools body))))
      (is (= "structured_output" (get-in body [:tools 0 :function :name])))
      (is (= schema (get-in body [:tools 0 :function :parameters])))
      (is (= 1 (count (:messages body)))) ; only user msg, no system prompt
      ))

  (testing "explicit tool_choice is preserved"
    (let [body (req-body {:input [{:role :user :content "go"}]
                          :model "gpt-4o"
                          :tools [{:tool-name "sql" :schema ['=> ['cat :any] :any] :doc "doc"}]
                          :tool_choice {"type" "function" "function" {"name" "sql"}}})]
      (is (= {"type" "function" "function" {"name" "sql"}} (:tool_choice body)))))

  (testing "temperature and max-tokens are propagated"
    (let [body (req-body {:input [{:role :user :content "go"}]
                          :model "gpt-4o"
                          :temperature 0.7
                          :max-tokens 100})]
      (is (= 0.7 (:temperature body)))
      (is (= 100 (:max_tokens body))))))

;;; ---------------------------------------------------------------------------
;;; Plain text streaming

(deftest text-stream-single-delta-test
  (testing "single text delta emits well-formed text chunks"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant" :content "Hello"}}]}
                                {:id "m1"
                                 :choices [{:delta {} :finish_reason "stop"}]}])]
      (is (= [{:type :text-start :id "m1"}
              {:type :text-delta  :id "m1" :delta "Hello"}]
             parts)))))

(deftest text-stream-multi-delta-test
  (testing "multiple text deltas are streamed through"
    (let [parts (chunks->parts [{:id "m1" :choices [{:delta {:role "assistant" :content "Hel"}}]}
                                {:id "m1" :choices [{:delta {:content "lo "}}]}
                                {:id "m1" :choices [{:delta {:content "world"}}]}
                                {:id "m1" :choices [{:delta {} :finish_reason "stop"}]}])]
      (is (= [{:type :text-start :id "m1"}
              {:type :text-delta  :id "m1" :delta "Hel"}
              {:type :text-delta  :id "m1" :delta "lo "}
              {:type :text-delta  :id "m1" :delta "world"}]
             parts)))))

(deftest empty-content-does-not-start-text-test
  (testing "empty content deltas (common with SiliconFlow/Kimi before tool calls) are ignored"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant" :content ""}}]}
                                {:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "f" :arguments ""}}]}}
                                           :finish_reason "tool_calls"]}])]
      ;; Only tool chunks, no text chunks from the empty content.
      (is (every? #{:tool-input-start :tool-input-available} (map :type parts))))))

;;; ---------------------------------------------------------------------------
;;; Tool-call streaming / aggregation

(deftest tool-call-single-chunk-test
  (testing "tool arguments that arrive in one chunk are emitted correctly"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "f"
                                                                             :arguments "{\"x\":1}"}}]}}
                                           :finish_reason "tool_calls"]}])]
      (is (= [{:type           :tool-input-start
               :toolCallId     "call_1"
               :toolName       "f"
               :inputTextDelta "{\"x\":1}"}
              {:type           :tool-input-available
               :toolCallId     "call_1"
               :toolName       "f"}]
             parts)))))

(deftest tool-call-split-deltas-test
  (testing "tool arguments split across many deltas are aggregated into one tool-input-start"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "structured_output"
                                                                             :arguments ""}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "{\""}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "questions"}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "\":["}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "\"hi\"]"}}]}}
                                           :finish_reason "tool_calls"]}])]
      (is (= 2 (count parts)))
      (is (= :tool-input-start (:type (first parts))))
      (is (= "call_1" (:toolCallId (first parts))))
      (is (= "structured_output" (:toolName (first parts))))
      (is (= "{\"questions\":[\"hi\"]" (:inputTextDelta (first parts))))
      (is (= :tool-input-available (:type (second parts)))))))

(deftest tool-call-missing-id-on-deltas-test
  (testing "subsequent tool deltas without id do not create extra groups"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "f"
                                                                             :arguments ""}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :id nil
                                                                  :function {:arguments "{"}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "}"}}]}}
                                           :finish_reason "tool_calls"]}])]
      (is (= 2 (count parts)))
      (is (= "call_1" (:toolCallId (first parts))))
      (is (= "{}" (:inputTextDelta (first parts)))))))

(deftest tool-call-missing-name-on-deltas-test
  (testing "tool name is captured from first chunk even when later deltas omit it"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "my_tool"
                                                                             :arguments ""}}]}}]}
                                {:id "m1"
                                 :choices [{:delta {:tool_calls [{:index 0
                                                                  :function {:arguments "123"}}]}}
                                           :finish_reason "tool_calls"]}])]
      (is (= "my_tool" (:toolName (first parts)))))))

(deftest completion-flush-emits-partial-tool-test
  (testing "if stream ends without finish_reason=tool_calls, leftover args are still flushed"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "f"
                                                                             :arguments "{\"incomplete"}}]}}]}])]
      (is (= 2 (count parts)))
      (is (= :tool-input-start (:type (first parts))))
      (is (= "{\"incomplete" (:inputTextDelta (first parts))))
      (is (= :tool-input-available (:type (second parts)))))))

;;; ---------------------------------------------------------------------------
;;; Usage chunk handling

(deftest incremental-usage-is-deferred-test
  (testing "SiliconFlow-like incremental usage chunks do not interrupt stream"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant" :content "Hello"}}]
                                 :usage {:prompt_tokens 10 :completion_tokens 1}}
                                {:id "m1"
                                 :choices [{:delta {:content " world"}}]
                                 :usage {:prompt_tokens 10 :completion_tokens 2}}
                                {:id "m1"
                                 :choices [{:delta {} :finish_reason "stop"}]
                                 :usage {:prompt_tokens 10 :completion_tokens 3}}])]
      (is (= 4 (count parts)))
      (is (= [{:type :text-start}
              {:type :text-delta :delta "Hello"}
              {:type :text-delta :delta " world"}]
             (map #(select-keys % [:type :delta]) (butlast parts))))
      (let [usage (last parts)]
        (is (= :usage (:type usage)))
        (is (= 10 (-> usage :usage :promptTokens)))
        (is (= 3 (-> usage :usage :completionTokens)))))))

(deftest usage-only-stream-test
  (testing "a stream that only carries usage still emits usage at completion"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{}]
                                 :usage {:prompt_tokens 5 :completion_tokens 7}}])]
      (is (= 1 (count parts)))
      (is (= :usage (:type (first parts))))
      (is (= 5 (-> (first parts) :usage :promptTokens)))
      (is (= 7 (-> (first parts) :usage :completionTokens))))))

;;; ---------------------------------------------------------------------------
;;; Mixed / edge cases

(deftest text-flushed-when-no-stop-test
  (testing "text is streamed without text-end because upstream aisdk-xf does not support it"
    (let [parts (chunks->parts [{:id "m1" :choices [{:delta {:role "assistant" :content "unterminated"}}]}])]
      (is (= [{:type :text-start :id "m1"}
              {:type :text-delta  :id "m1" :delta "unterminated"}]
             parts)))))

(deftest multi-tool-call-test
  (testing "parallel tool calls are accumulated and emitted in index order"
    (let [parts (chunks->parts [{:id "m1"
                                 :choices [{:delta {:role "assistant"
                                                    :tool_calls [{:index 0
                                                                  :id "call_1"
                                                                  :function {:name "f1" :arguments "{a:1}"}}
                                                                 {:index 1
                                                                  :id "call_2"
                                                                  :function {:name "f2" :arguments "{b:2}"}}]}}
                                           :finish_reason "tool_calls"]}])]
      (is (= 4 (count parts)))
      (is (= [{:toolCallId "call_1" :toolName "f1" :inputTextDelta "{a:1}"}
              {:toolCallId "call_2" :toolName "f2" :inputTextDelta "{b:2}"}]
             (keep #(when (= :tool-input-start (:type %))
                      (select-keys % [:toolCallId :toolName :inputTextDelta]))
                   parts))))))

(deftest tool-output-keeps-id-test
  (testing "tool output messages preserve the upstream tool call id"
    (let [messages (parts->messages nil
                                    [{:type :tool-input
                                      :id "call_abc"
                                      :function "my_tool"
                                      :arguments {:x 1}}
                                     {:type :tool-output
                                      :id "call_abc"
                                      :result {:output "ok"}}])]
      (is (= "call_abc" (-> messages last :tool_call_id)))
      (is (= 1 (->> messages
                    (filter #(= "assistant" (:role %)))
                    first
                    :tool_calls
                    count))))))

(deftest marker-log-present-test
  (testing "the custom adapter exposes a marker string for runtime verification"
    (is (some? (var-get #'custom/custom-provider-marker)))
    (is (string? (var-get #'custom/custom-provider-marker)))))
