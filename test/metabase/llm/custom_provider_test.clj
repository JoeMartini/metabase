(ns metabase.llm.custom-provider-test
  "Tests for custom LLM provider settings and API routing."
  (:require
   [clojure.test :refer :all]
   [metabase.llm.settings :as llm.settings]
   [metabase.metabot.settings :as metabot.settings]
   [metabase.test :as mt]))

(set! *warn-on-reflection* true)

;;; -------------------------------------------------- Settings Tests --------------------------------------------------

(deftest custom-provider-settings-test
  (testing "custom provider settings can be set and read"
    (mt/with-temporary-setting-values
      [llm-custom-provider-enabled? true
       llm-custom-provider-name "SiliconFlow"
       llm-custom-provider-base-url "https://api.siliconflow.cn/v1"
       llm-custom-provider-api-key "sk-test-siliconflow"
       llm-custom-provider-model "deepseek-ai/DeepSeek-V3"]
      (is (true? (llm.settings/llm-custom-provider-enabled?)))
      (is (= "SiliconFlow" (llm.settings/llm-custom-provider-name)))
      (is (= "https://api.siliconflow.cn/v1" (llm.settings/llm-custom-provider-base-url)))
      (is (= "sk-test-siliconflow" (llm.settings/llm-custom-provider-api-key)))
      (is (= "deepseek-ai/DeepSeek-V3" (llm.settings/llm-custom-provider-model)))))

  (testing "custom provider defaults are correct"
    (mt/with-temporary-setting-values
      [llm-custom-provider-enabled? false
       llm-custom-provider-name nil
       llm-custom-provider-base-url nil
       llm-custom-provider-api-key nil
       llm-custom-provider-model nil]
      (is (false? (llm.settings/llm-custom-provider-enabled?)))
      (is (= "Custom Provider" (llm.settings/llm-custom-provider-name)))
      (is (= "https://api.siliconflow.cn/v1" (llm.settings/llm-custom-provider-base-url)))
      (is (nil? (llm.settings/llm-custom-provider-api-key)))
      (is (= "deepseek-ai/DeepSeek-V3" (llm.settings/llm-custom-provider-model))))))

;;; -------------------------------------------------- Provider Selection Tests --------------------------------------------------

(deftest custom-provider-configured-test
  (testing "custom provider is configured when enabled and has api key"
    (mt/with-temporary-setting-values
      [llm-custom-provider-enabled? true
       llm-custom-provider-api-key "sk-test"
       llm-metabot-provider "custom/deepseek-v3"]
      (is (true? (metabot.settings/llm-metabot-configured?)))))

  (testing "custom provider is not configured when disabled"
    (mt/with-temporary-setting-values
      [llm-custom-provider-enabled? false
       llm-custom-provider-api-key "sk-test"
       llm-metabot-provider "custom/deepseek-v3"]
      (is (false? (metabot.settings/llm-metabot-configured?)))))

  (testing "custom provider is not configured when api key is missing"
    (mt/with-temporary-setting-values
      [llm-custom-provider-enabled? true
       llm-custom-provider-api-key nil
       llm-metabot-provider "custom/deepseek-v3"]
      (is (false? (metabot.settings/llm-metabot-configured?))))))

;;; -------------------------------------------------- Provider Validation Tests --------------------------------------------------

(deftest custom-provider-validation-test
  (testing "custom provider is in supported providers list"
    (is (contains? metabot.settings/supported-metabot-providers "custom")))

  (testing "custom provider model string is valid"
    (mt/with-temporary-setting-values [llm-metabot-provider "custom/deepseek-v3"]
      (is (= "custom/deepseek-v3" (metabot.settings/llm-metabot-provider))))))
