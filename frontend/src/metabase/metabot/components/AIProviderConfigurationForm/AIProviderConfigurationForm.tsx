import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { P, match } from "ts-pattern";
import { t } from "ttag";

import {
  useUpdateMetabotSettingsMutation,
  useUpdateSettingsMutation,
} from "metabase/api";
import {
  getErrorMessage,
  useAdminSetting,
  useAdminSettings,
} from "metabase/api/utils";
import { ConfirmModal } from "metabase/common/components/ConfirmModal";
import { SetByEnvVar } from "metabase/common/components/SetByEnvVar";
import { useSetting, useToast } from "metabase/common/hooks";
import { PLUGIN_METABOT } from "metabase/plugins";
import { Button, Flex, Group, Select, Stack, Text } from "metabase/ui";
import type { MetabotProvider } from "metabase-types/api";

import { AIProviderConfigurationContext } from "./AIProviderConfigurationContext";
import { ApiKeyProviderFields } from "./ApiKeyProviderFields";
import { BedrockProviderFields } from "./BedrockProviderFields";
import {
  API_KEY_SETTING_BY_PROVIDER,
  getProviderOptions,
  isAvailableProvider,
  parseProviderAndModel,
} from "./utils";

export function AIProviderConfigurationForm({
  isModal = false,
  onClose,
}: {
  isModal?: boolean;
  onClose?: VoidFunction;
}) {
  const MetabaseAIProviderSetup = PLUGIN_METABOT.MetabaseAIProviderSetup;
  const offerMetabaseAiManaged = PLUGIN_METABOT.isEnabled;
  const [sendToast] = useToast();

  const { value: savedProviderValue, settingDetails } = useAdminSetting(
    "llm-metabot-provider",
  );
  const isEnvSetting =
    !!settingDetails &&
    !!settingDetails.is_env_setting &&
    !!settingDetails.env_name;
  const envSettingName = isEnvSetting ? settingDetails?.env_name : undefined;

  const isConfigured = !!useSetting("llm-metabot-configured?");

  const config = useMemo(
    () => parseProviderAndModel(savedProviderValue),
    [savedProviderValue],
  );
  const connectedProvider = isConfigured ? config?.provider : undefined;
  const connectedModel = isConfigured ? config?.model : undefined;
  const [provider, setProvider] = useState<MetabotProvider | undefined>(
    isModal ? undefined : connectedProvider,
  );

  const isCurrentConfigured = connectedProvider === provider && isConfigured;

  useEffect(() => {
    if (isModal) {
      return;
    }
    setProvider(connectedProvider);
  }, [isModal, connectedProvider]);

  const [updateSettings, updateSettingsResult] = useUpdateSettingsMutation();
  const [updateMetabotSettings] = useUpdateMetabotSettingsMutation();
  const disconnectHandlerRef = useRef<(() => Promise<void>) | null>(null);

  const { details: providerApiKeyDetails } = useAdminSettings([
    "llm-anthropic-api-key",
    "llm-openai-api-key",
    "llm-openrouter-api-key",
    "llm-custom-provider-api-key",
  ] as const);

  const { details: customProviderDetails } = useAdminSettings([
    "llm-custom-provider-base-url",
  ] as const);

  const disconnectProvider = useCallback(async () => {
    if (!connectedProvider) {
      return;
    }

    try {
      await disconnectHandlerRef.current?.();
    } catch {
      return;
    }

    const settingsToClear: Record<string, null> = {
      "llm-metabot-provider": null,
    };

    if (connectedProvider !== "metabase" && connectedProvider !== "bedrock") {
      const apiKeySettingKey = API_KEY_SETTING_BY_PROVIDER[connectedProvider];
      const apiKeySetting = providerApiKeyDetails[apiKeySettingKey];

      if (!apiKeySetting?.is_env_setting) {
        settingsToClear[apiKeySettingKey] = null;
      }
    }

    try {
      if (connectedProvider === "bedrock") {
        // Bedrock key material spans several settings; an explicit `credentials: null`
        // clears them all in one call. It runs before the provider is deselected so a
        // failure can't leave saved keys behind.
        await updateMetabotSettings({
          provider: "bedrock",
          credentials: null,
        }).unwrap();
      }

      const response = await updateSettings(settingsToClear);

      if (response.error) {
        const message = getErrorMessage(
          response.error,
          t`Unable to save provider settings.`,
        );

        sendToast({
          message,
          icon: "warning",
          toastColor: "error",
        });
      }
    } catch (error) {
      const message = getErrorMessage(
        error,
        t`Unable to save provider settings.`,
      );

      sendToast({
        message,
        icon: "warning",
        toastColor: "error",
      });
    }
  }, [
    connectedProvider,
    disconnectHandlerRef,
    providerApiKeyDetails,
    updateMetabotSettings,
    updateSettings,
    sendToast,
  ]);

  const providerOptions = useMemo(() => {
    const options = Object.values(getProviderOptions(offerMetabaseAiManaged));
    return options.map((option) => ({
      ...option,
      disabled: !isAvailableProvider(option.value),
    }));
  }, [offerMetabaseAiManaged]);

  const connectHandlerRef = useRef<(() => Promise<void>) | null>(null);

  const [isDisconnecting, setIsDisconnecting] = useState(false);
  const [isDisconnectConfirmOpen, setIsDisconnectConfirmOpen] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const handleConnect = async () => {
    if (!connectHandlerRef.current) {
      return;
    }
    setIsConnecting(true);
    try {
      await connectHandlerRef.current();
    } finally {
      setIsConnecting(false);
    }
  };

  const resetProvider = () => {
    setProvider(undefined);
  };

  const handleDisconnect = useCallback(() => {
    setIsDisconnectConfirmOpen(true);
  }, []);

  const handleConfirmDisconnect = async () => {
    setIsDisconnecting(true);
    try {
      await disconnectProvider();
      setIsDisconnectConfirmOpen(false);
    } finally {
      setIsDisconnecting(false);
    }
  };

  const isMutating =
    isConnecting || isDisconnecting || updateSettingsResult.isLoading;

  const [isConnectButtonEnabled, setIsConnectButtonEnabled] = useState(false);

  return (
    <AIProviderConfigurationContext.Provider
      value={{
        connectHandlerRef,
        disconnectHandlerRef,
        isMutating,
        setIsConnectButtonEnabled,
        isConnectButtonEnabled,
        resetProvider,
        handleDisconnect,
        isModal: !!isModal,
      }}
    >
      <Stack gap="md">
        {!isCurrentConfigured && (
          <Select
            label={t`Provider`}
            placeholder={t`Select a provider`}
            data={providerOptions}
            value={provider}
            onChange={setProvider}
            disabled={isEnvSetting || isMutating}
            renderOption={({ option }) => (
              <Group
                gap="xs"
                p="sm"
                justify="space-between"
                wrap="nowrap"
                w="100%"
              >
                <Text
                  lh="1rem"
                  c={option.disabled ? "text-tertiary" : undefined}
                >
                  {option.label}
                </Text>
                {!isAvailableProvider(option.value as MetabotProvider) && (
                  <Text c="text-tertiary" lh="1rem" size="sm">
                    {t`Coming soon`}
                  </Text>
                )}
              </Group>
            )}
          />
        )}

        {match(provider)
          .with("metabase", () => (
            <MetabaseAIProviderSetup onConnect={onClose} />
          ))
          .with("bedrock", () => (
            <BedrockProviderFields
              connectedModel={connectedModel}
              isCurrentConfigured={isCurrentConfigured}
              isEnvSetting={isEnvSetting}
            />
          ))
          .with("anthropic", "openai", "openrouter", (selectedProvider) => (
            <ApiKeyProviderFields
              key={selectedProvider}
              selectedProvider={selectedProvider}
              connectedModel={connectedModel}
              isCurrentConfigured={isCurrentConfigured}
              isEnvSetting={isEnvSetting}
            />
          ))
          .with(P.nullish, () => null)
          .exhaustive()}

        {envSettingName && <SetByEnvVar varName={envSettingName} />}

        <Flex justify="end">
          {match({ isCurrentConfigured, isConnectButtonEnabled, isModal })
            .with({ isModal: true, isCurrentConfigured: true }, () => (
              <Button
                variant="filled"
                loading={isMutating}
                disabled={isMutating}
                onClick={onClose}
              >
                {t`Done`}
              </Button>
            ))
            .with(
              { isCurrentConfigured: true, isConnectButtonEnabled: false },
              () => (
                <Button
                  c="danger"
                  loading={isMutating}
                  disabled={isMutating}
                  onClick={handleDisconnect}
                >
                  {t`Disconnect`}
                </Button>
              ),
            )
            .with(
              { isCurrentConfigured: false },
              { isCurrentConfigured: true, isConnectButtonEnabled: true },
              () => (
                <Button
                  variant="filled"
                  loading={isMutating}
                  disabled={isMutating || !isConnectButtonEnabled}
                  onClick={handleConnect}
                >
                  {t`Connect`}
                </Button>
              ),
            )
            .exhaustive()}
        </Flex>
        <ConfirmModal
          opened={isDisconnectConfirmOpen}
          onClose={() => setIsDisconnectConfirmOpen(false)}
          title={t`Disconnect AI provider?`}
          message={t`This will disconnect your AI provider and disable AI features across your instance until you connect a provider again.`}
          confirmButtonText={t`Disconnect provider`}
          onConfirm={handleConfirmDisconnect}
        />
      </Stack>
    </AIProviderConfigurationContext.Provider>
  );
}

const ProviderCredentialsFields = ({
  selectedProvider,
  connectedModel,
  isCurrentConfigured,
  isEnvSetting,
}: {
  selectedProvider: Exclude<MetabotProvider, "metabase">;
  connectedModel: string | undefined;
  isCurrentConfigured: boolean;
  isEnvSetting: boolean;
}) => {
  const [model, setModel] = useState<string | undefined>(connectedModel);
  const [apiKeyLocalValue, setApiKeyLocalValue] = useState<string | null>(null);
  const [baseUrlLocalValue, setBaseUrlLocalValue] = useState<string | null>(null);
  const [sendToast] = useToast();

  useEffect(() => {
    setModel(connectedModel);
  }, [connectedModel]);

  const [updateMetabotSettings, updateMetabotSettingsResult] =
    useUpdateMetabotSettingsMutation();

  const onConnect = async () => {
    await updateMetabotSettings({
      provider: selectedProvider,
      "api-key": apiKeyLocalValue || null,
      "base-url": baseUrlLocalValue || null,
      model: model || null,
    }).unwrap();

    setApiKeyLocalValue(null);
    setBaseUrlLocalValue(null);
  };

  const hasDirtyApiKey = apiKeyLocalValue !== null;
  const connectHandler =
    !isCurrentConfigured || hasDirtyApiKey ? onConnect : null;

  const { isMutating } = useAIProviderConfigurationContext(connectHandler);

  const { details: providerApiKeyDetails } = useAdminSettings([
    "llm-anthropic-api-key",
    "llm-openai-api-key",
    "llm-openrouter-api-key",
    "llm-custom-provider-api-key",
  ] as const);

  const { details: customProviderDetails } = useAdminSettings([
    "llm-custom-provider-base-url",
  ] as const);

  const selectedApiKeySetting =
    providerApiKeyDetails[API_KEY_SETTING_BY_PROVIDER[selectedProvider]];
  const selectedApiKeyValue = String(selectedApiKeySetting?.value ?? "");

  const selectedBaseUrlSetting = customProviderDetails["llm-custom-provider-base-url"];
  const selectedBaseUrlValue = String(selectedBaseUrlSetting?.value ?? "");
  const apiKeyEnvSettingName = selectedApiKeySetting?.is_env_setting
    ? selectedApiKeySetting.env_name
    : undefined;
  const needsApiKey = !hasConfiguredSettingValue(selectedApiKeySetting);

  const metabotSettingsQuery = useGetMetabotSettingsQuery(
    {
      provider: selectedProvider,
    },
    { skip: needsApiKey },
  );

  const modelOptions = useMemo(
    () => getLlmModelOptions(metabotSettingsQuery.currentData?.models ?? []),
    [metabotSettingsQuery.currentData?.models],
  );

  const modelError = getModelError(
    metabotSettingsQuery.error,
    selectedProvider,
  );
  const apiKeyError = hasDirtyApiKey
    ? undefined
    : (metabotSettingsQuery.currentData?.["api-key-error"] ?? undefined);

  const displayApiKeyValue = apiKeyLocalValue ?? selectedApiKeyValue;

  useEffect(() => {
    setApiKeyLocalValue(null);
    setBaseUrlLocalValue(null);
  }, [selectedProvider, selectedApiKeySetting?.value]);

  const handleApiKeyChange = (event: ChangeEvent<HTMLInputElement>) => {
    setApiKeyLocalValue(event.target.value);
  };

  const handleBaseUrlChange = (event: ChangeEvent<HTMLInputElement>) => {
    setBaseUrlLocalValue(event.target.value);
  };

  const handleModelChange = async (value: string) => {
    setModel(value);

    if (!value) {
      return;
    }

    await updateMetabotSettings({
      provider: selectedProvider,
      model: value,
    }).unwrap();

    sendToast({
      message: t`Settings saved successfully`,
      icon: "check",
    });
  };

  const selectedProviderDetails = getProviderOptions(true)[selectedProvider];

  return (
    <>
      <TextInput
        key={selectedProvider}
        label={t`API key`}
        type="password"
        description={
          <ExternalLink
            key={selectedProviderDetails.value}
            href={selectedProviderDetails.apiKey.addKeyUrl}
          >
            {c("{0} is the name of an AI provider")
              .t`Get or manage keys in ${selectedProviderDetails.label}`}
          </ExternalLink>
        }
        placeholder={
          selectedProviderDetails.apiKey?.placeholder ?? t`Enter your API key`
        }
        value={displayApiKeyValue}
        error={apiKeyError}
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
          placeholder="https://api.siliconflow.com/v1"
          value={baseUrlLocalValue ?? selectedBaseUrlValue}
          onChange={handleBaseUrlChange}
          disabled={isMutating || isEnvSetting}
          w="100%"
        />
      )}

      {!needsApiKey && !apiKeyError && selectedProvider !== "custom" && (
        <Select
          label={t`Model`}
          placeholder={
            metabotSettingsQuery.isLoading
              ? t`Loading models...`
              : t`Select a model`
          }
          description={getModelDescription(selectedProvider)}
          error={modelError}
          data={modelOptions}
          value={model}
          onChange={handleModelChange}
          disabled={isEnvSetting || needsApiKey || isMutating}
          searchable
          nothingFoundMessage={t`No models found`}
        />
      )}

      {selectedProvider === "custom" && (
        <TextInput
          label={t`Model`}
          type="text"
          placeholder="deepseek-ai/DeepSeek-V3"
          description={getModelDescription(selectedProvider)}
          value={model ?? ""}
          onChange={(e) => handleModelChange(e.target.value)}
          disabled={isMutating || isEnvSetting}
          w="100%"
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

const getLlmModelOptions = (models: MetabotSettingsResponse["models"]) => {
  const modelOptions = models.map((m) => ({
    value: m.id,
    label: m.display_name,
    group: m.group,
  }));

  const sel = (o: MetabotModelOption) => _.pick(o, ["value", "label"]);
  // group model options if needed
  return _.every(modelOptions, (o) => !o.group)
    ? modelOptions.map(sel)
    : _.map(
        _.groupBy(modelOptions, (o) => o.group ?? t`Other`),
        (items, group) => ({ group, items: items.map(sel) }),
      );
};

const hasConfiguredSettingValue = (setting: SettingDefinition | undefined) =>
  Boolean(setting?.value || setting?.is_env_setting);

const getModelError = (error: unknown, provider?: MetabotProvider) =>
  !provider || !error
    ? undefined
    : getErrorMessage(error, t`Unable to load models.`);
