import { type ChangeEvent, useEffect, useState } from "react";
import { c, t } from "ttag";

import { useUpdateMetabotSettingsMutation } from "metabase/api";
import { getErrorMessage } from "metabase/api/utils";
// eslint-disable-next-line no-restricted-imports
import { useAdminSettings } from "metabase/api/utils/settings";
import { ExternalLink } from "metabase/common/components/ExternalLink";
import { SetByEnvVar } from "metabase/common/components/SetByEnvVar";
import { Text, TextInput } from "metabase/ui";

import { useAIProviderConfigurationContext } from "./AIProviderConfigurationContext";
import {
  ProviderModelPicker,
  useProviderModelsQuery,
} from "./ProviderModelPicker";
import {
  API_KEY_SETTING_BY_PROVIDER,
  type MetabotApiKeyProvider,
  getProviderOptions,
  hasConfiguredSettingValue,
} from "./utils";

export const ApiKeyProviderFields = ({
  selectedProvider,
  connectedModel,
  isCurrentConfigured,
  isEnvSetting,
}: {
  selectedProvider: MetabotApiKeyProvider;
  connectedModel: string | undefined;
  isCurrentConfigured: boolean;
  isEnvSetting: boolean;
}) => {
  const [localApiKey, setLocalApiKey] = useState<string | null>(null);
  const [localBaseUrl, setLocalBaseUrl] = useState<string | null>(null);
  const [updateMetabotSettings, updateMetabotSettingsResult] =
    useUpdateMetabotSettingsMutation();

  const settingsToFetch =
    selectedProvider === "custom"
      ? ([
          "llm-anthropic-api-key",
          "llm-openai-api-key",
          "llm-openrouter-api-key",
          "llm-custom-provider-api-key",
          "llm-custom-provider-base-url",
        ] as const)
      : ([
          "llm-anthropic-api-key",
          "llm-openai-api-key",
          "llm-openrouter-api-key",
        ] as const);

  const { details } = useAdminSettings(settingsToFetch);
  const apiKeySetting = details[API_KEY_SETTING_BY_PROVIDER[selectedProvider]];
  const apiKeyEnvSettingName = apiKeySetting?.is_env_setting
    ? apiKeySetting.env_name
    : undefined;

  const baseUrlSetting =
    selectedProvider === "custom"
      ? details["llm-custom-provider-base-url"]
      : undefined;

  const onConnect = async () => {
    await updateMetabotSettings({
      provider: selectedProvider,
      "api-key": localApiKey || null,
      "base-url": selectedProvider === "custom" ? localBaseUrl || null : null,
      model: connectedModel || null,
    }).unwrap();

    setLocalApiKey(null);
    setLocalBaseUrl(null);
  };

  const hasDirtyApiKey = localApiKey !== null;
  const hasDirtyBaseUrl = localBaseUrl !== null;
  const connectHandler =
    !isCurrentConfigured || hasDirtyApiKey || hasDirtyBaseUrl
      ? onConnect
      : null;
  const { isMutating } = useAIProviderConfigurationContext(connectHandler);

  const needsApiKey = !hasConfiguredSettingValue(apiKeySetting);
  const { modelsQuery, credentialsError: savedCredentialsError } =
    useProviderModelsQuery(selectedProvider, { skip: needsApiKey });
  const credentialsError = hasDirtyApiKey ? undefined : savedCredentialsError;

  const apiKeySettingValue = apiKeySetting?.value;
  const baseUrlSettingValue = baseUrlSetting?.value;

  useEffect(() => {
    setLocalApiKey(null);
    setLocalBaseUrl(null);
  }, [apiKeySettingValue, baseUrlSettingValue]);

  const handleApiKeyChange = (event: ChangeEvent<HTMLInputElement>) => {
    setLocalApiKey(event.target.value);
  };

  const handleBaseUrlChange = (event: ChangeEvent<HTMLInputElement>) => {
    setLocalBaseUrl(event.target.value);
  };

  const providerDetails = getProviderOptions(true)[selectedProvider];

  return (
    <>
      <TextInput
        label={t`API key`}
        type="password"
        description={
          providerDetails.apiKey.addKeyUrl ? (
            <ExternalLink href={providerDetails.apiKey.addKeyUrl}>
              {c("{0} is the name of an AI provider")
                .t`Get or manage keys in ${providerDetails.label}`}
            </ExternalLink>
          ) : null
        }
        placeholder={providerDetails.apiKey.placeholder}
        value={localApiKey ?? String(apiKeySettingValue ?? "")}
        error={credentialsError}
        onChange={handleApiKeyChange}
        disabled={isMutating || isEnvSetting || !!apiKeyEnvSettingName}
        w="100%"
      />

      {apiKeyEnvSettingName ? (
        <SetByEnvVar varName={apiKeyEnvSettingName} />
      ) : null}

      {selectedProvider === "custom" && (
        <TextInput
          label={t`Base URL`}
          type="text"
          placeholder="https://api.siliconflow.cn/v1"
          value={localBaseUrl ?? String(baseUrlSettingValue ?? "")}
          onChange={handleBaseUrlChange}
          disabled={isMutating || isEnvSetting}
          w="100%"
        />
      )}

      {!needsApiKey && !credentialsError && (
        <ProviderModelPicker
          provider={selectedProvider}
          connectedModel={connectedModel}
          models={modelsQuery.currentData?.models ?? []}
          isLoading={modelsQuery.isLoading}
          loadError={modelsQuery.error}
          disabled={isEnvSetting || isMutating}
        />
      )}

      {updateMetabotSettingsResult.error && (
        <Text size="sm" c="error">
          {getErrorMessage(
            updateMetabotSettingsResult.error,
            t`Unable to save provider settings.`,
          )}
        </Text>
      )}
    </>
  );
};
