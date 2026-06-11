import { Api } from "./api";
import { invalidateTags, tag } from "./tags";

export interface OidcCheckRequest {
  "issuer-uri": string;
  "client-id": string;
  "client-secret"?: string | null;
  key?: string | null;
}

export interface OidcCheckStepResult {
  step: string;
  success: boolean;
  verified?: boolean;
  error?: string;
  "token-endpoint"?: string;
}

export interface OidcCheckResponse {
  ok: boolean;
  discovery: OidcCheckStepResult;
  credentials?: OidcCheckStepResult;
}

export interface CustomOidcConfig {
  key: string;
  "login-prompt": string;
  "issuer-uri": string;
  "client-id": string;
  "client-secret"?: string;
  scopes?: string[];
  enabled?: boolean;
  "attribute-map"?: Record<string, string>;
  "group-sync"?: {
    enabled?: boolean;
    "group-attribute"?: string;
    "group-mappings"?: Record<string, number[]>;
  };
}

export const oidcApi = Api.injectEndpoints({
  endpoints: (builder) => ({
    getOidcProviders: builder.query<CustomOidcConfig[], void>({
      query: () => ({
        method: "GET",
        url: "/api/oidc/providers",
      }),
      providesTags: [tag("session-properties")],
    }),
    getOidcProvider: builder.query<CustomOidcConfig, string>({
      query: (key) => ({
        method: "GET",
        url: `/api/oidc/providers/${key}`,
      }),
      providesTags: [tag("session-properties")],
    }),
    createOidcProvider: builder.mutation<CustomOidcConfig, CustomOidcConfig>({
      query: (provider) => ({
        method: "POST",
        url: "/api/oidc/providers",
        body: provider,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("session-properties")]),
    }),
    updateOidcProvider: builder.mutation<
      CustomOidcConfig,
      { key: string; provider: Partial<CustomOidcConfig> }
    >({
      query: ({ key, provider }) => ({
        method: "PUT",
        url: `/api/oidc/providers/${key}`,
        body: provider,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("session-properties")]),
    }),
    deleteOidcProvider: builder.mutation<void, string>({
      query: (key) => ({
        method: "DELETE",
        url: `/api/oidc/providers/${key}`,
      }),
      invalidatesTags: (_, error) =>
        invalidateTags(error, [tag("session-properties")]),
    }),
    checkOidcConnection: builder.mutation<OidcCheckResponse, OidcCheckRequest>({
      query: (body) => ({
        method: "POST",
        url: "/api/oidc/check",
        body,
      }),
    }),
  }),
});

export const {
  useGetOidcProvidersQuery,
  useGetOidcProviderQuery,
  useCreateOidcProviderMutation,
  useUpdateOidcProviderMutation,
  useDeleteOidcProviderMutation,
  useCheckOidcConnectionMutation,
} = oidcApi;
