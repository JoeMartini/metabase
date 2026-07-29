(ns metabase.sso.providers.oidc
  "Base OIDC authentication provider. Provides generic OIDC support that concrete
   implementations (Auth0, Okta, etc.) can derive from."
  (:require
   [metabase.auth-identity.core :as auth-identity]
   [metabase.sso.core :as sso]
   [metabase.sso.oidc.common :as oidc.common]
   [metabase.sso.oidc.discovery :as oidc.discovery]
   [metabase.sso.oidc.http :as oidc.http]
   [metabase.sso.oidc.schema :as oidc.schema]
   [metabase.sso.oidc.state :as oidc.state]
   [metabase.sso.oidc.tokens :as oidc.tokens]
   [metabase.sso.settings :as sso.settings]
   [metabase.util :as u]
   [metabase.util.log :as log]
   [methodical.core :as methodical]))

;;; -------------------------------------------------- Provider Registration --------------------------------------------------

;; Register the OIDC provider in the hierarchy
(derive :provider/oidc :metabase.auth-identity.provider/provider)
(derive :provider/oidc :metabase.auth-identity.provider/create-user-if-not-exists)

;;; -------------------------------------------------- Configuration Handling --------------------------------------------------

(defn- enrich-config-with-discovery
  "Enrich configuration with OIDC discovery endpoints if needed.

   If the configuration doesn't have manual endpoints, attempts discovery
   using the issuer URI.

   Returns updated configuration map with :discovery-document."
  [config]
  (if (oidc.schema/discovery-based? config)
    ;; Use discovery
    (if-let [discovery-doc (oidc.discovery/discover-oidc-configuration (:issuer-uri config))]
      (assoc config :discovery-document discovery-doc)
      (do
        (log/warnf "OIDC discovery failed for issuer %s, falling back to manual configuration" (:issuer-uri config))
        config))
    config))

;;; -------------------------------------------------- Token Exchange --------------------------------------------------

(defn- exchange-code-for-tokens
  "Exchange authorization code for tokens at the token endpoint.

   Parameters:
   - code: Authorization code
   - config: Enriched OIDC configuration with discovery document (if applicable),
             token endpoint, client credentials
   - redirect-uri: The redirect URI used in the authorization request

   Returns token response map with :id-token, :access-token, etc."
  [code config redirect-uri]
  (let [token-endpoint (oidc.discovery/get-token-endpoint config)]
    (try
      (let [response (oidc.http/oidc-post token-endpoint
                                          {:form-params {:grant_type "authorization_code"
                                                         :code code
                                                         :redirect_uri redirect-uri
                                                         :client_id (:client-id config)
                                                         :client_secret (:client-secret config)}})]
        (if (= 200 (:status response))
          (oidc.common/parse-token-response (:body response))
          (do
            (log/errorf "Token exchange failed with status %s" (:status response))
            nil)))
      (catch Exception e
        (log/errorf "Token exchange failed: %s" (ex-message e))
        nil))))

;;; -------------------------------------------------- User Data Extraction --------------------------------------------------

(defn- extract-user-data
  "Extract user data from ID token claims.

   Parameters:
   - claims: ID token claims map
   - config: OIDC configuration (for custom attribute mappings)

   Returns user data map with :email, :first_name, :last_name, :provider-id, :groups"
  [claims config]
  (let [;; Get attribute mappings from config, or use defaults
        email-attr (get config :attribute-email "email")
        firstname-attr (get config :attribute-firstname "given_name")
        lastname-attr (get config :attribute-lastname "family_name")
        groups-attr (get config :attribute-groups (sso.settings/oidc-attribute-groups))

        ;; Extract values
        email (get claims (keyword email-attr))
        first-name (get claims (keyword firstname-attr))
        last-name (get claims (keyword lastname-attr))
        provider-id (:sub claims)
        groups (get claims (keyword groups-attr))]
    (when email
      {:email email
       :first_name first-name
       :last_name last-name
       :provider-id provider-id
       :sso_source :oidc
       :groups (when (seq groups) groups)})))

;;; -------------------------------------------------- Group Sync --------------------------------------------------

(defn- oidc-groups->mb-group-ids
  "Translate a set of a user's OIDC groups to a set of MB group IDs using the configured mappings."
  [oidc-groups]
  (let [group-mappings (sso.settings/oidc-group-mappings)]
    (->> oidc-groups
         (map #(or (get group-mappings %)
                   (get group-mappings (keyword %))))
         (remove nil?)
         flatten
         set)))

(defn- all-mapped-group-ids
  "Returns the set of all MB group IDs that have configured OIDC mappings."
  []
  (-> (sso.settings/oidc-group-mappings)
      vals
      flatten
      set))

;;; -------------------------------------------------- Authentication Implementation --------------------------------------------------

(methodical/defmethod auth-identity/authenticate :provider/oidc
  [_provider request]
  (let [config (or (when-let [provider-key (:oidc-provider-key request)]
                     (sso.settings/get-oidc-provider provider-key))
                   (oidc.common/extract-oidc-config request))]
    (cond
      ;; Configuration missing
      (not config)
      {:success? false
       :error :configuration-error
       :message "OIDC configuration not found in request"}

      ;; Callback handling (has authorization code or state or error)
      (some #(contains? request %) [:code :error :state])
      (let [;; Validate callback parameters
            validation (oidc.common/validate-callback-params request)]
        (if-not (:valid? validation)
          {:success? false
           :error :invalid-callback
           :message (get-in validation [:error :description] "Invalid callback parameters")}
          ;; Enrich config with discovery once for the entire callback flow
          (let [enriched-config (enrich-config-with-discovery config)
                redirect-uri (or (:redirect-uri request) (:redirect-uri config))
                code (:code validation)
                tokens (exchange-code-for-tokens code enriched-config redirect-uri)]
            (if-not (:id-token tokens)
              {:success? false
               :error :token-exchange-failed
               :message "Failed to exchange authorization code for tokens"}
              ;; Validate ID token
              (let [jwks-uri (oidc.discovery/get-jwks-uri enriched-config)
                    validation-config {:jwks-uri jwks-uri
                                       :issuer-uri (:issuer-uri config)
                                       :client-id (:client-id config)}
                    ;; Use :oidc-nonce to avoid collision with CSP :nonce from security middleware
                    nonce (:oidc-nonce request)
                    validation-result (oidc.tokens/validate-id-token (:id-token tokens)
                                                                     validation-config
                                                                     nonce)]
                (if-not (:valid? validation-result)
                  {:success? false
                   :error :invalid-token
                   :message (:error validation-result)}
                  ;; Extract user data from claims
                  (let [claims (:claims validation-result)
                        user-data (extract-user-data claims config)]
                    (if-not user-data
                      {:success? false
                       :error :user-data-extraction-failed
                       :message "Failed to extract user email from token"}
                      {:success? true
                       :claims claims
                       :user-data user-data
                       :provider-id (:provider-id user-data)}))))))))

      ;; Initiate authorization flow
      :else
      (let [enriched-config (enrich-config-with-discovery config)
            redirect-uri (or (:redirect-uri request) (:redirect-uri config))
            authorization-endpoint (oidc.discovery/get-authorization-endpoint enriched-config)]
        (if-not authorization-endpoint
          {:success? false
           :error :configuration-error
           :message "Authorization endpoint not found. Check OIDC configuration or discovery."}
          ;; Generate authorization URL
          (let [state (oidc.common/generate-state)
                nonce (oidc.common/generate-nonce)
                scopes (get config :scopes ["openid" "email" "profile"])
                auth-url (oidc.common/generate-authorization-url
                          authorization-endpoint
                          (:client-id config)
                          redirect-uri
                          scopes
                          state
                          nonce)]
            {:success? :redirect
             :redirect-url auth-url
             :message "Redirecting to OIDC provider for authentication"
             ;; Store state and nonce for validation on callback
             :state state
             :nonce nonce}))))))

;;; -------------------------------------------------- Login Implementation --------------------------------------------------

(methodical/defmethod auth-identity/login! :around :provider/oidc
  [provider {:keys [code state] :as request}]
  (let [result (if (and code state)
                 (let [;; Get provider-specific keyword from request or derive from provider
                       provider-keyword (or (:oidc-provider request) provider)
                       validation (oidc.state/validate-oidc-callback request
                                                                   state
                                                                   provider-keyword
                                                                   {:validate-browser-id (:browser-id request)})]
                   (if-not (:valid? validation)
                     {:success? false
                      :error (:error validation)
                      :message (:message validation)}
                     ;; Add nonce and redirect from validated state to request
                     ;; Use :oidc-nonce to avoid collision with CSP :nonce from security middleware
                     (next-method provider (cond-> (assoc request :oidc-nonce (:nonce validation))
                                             ;; Use redirect from state cookie if not already set in request
                                             (and (:redirect validation)
                                                  (not (:redirect-url request)))
                                             (assoc :redirect-url (:redirect validation))))))
                 ;; Not a callback - pass through to next method
                 (next-method provider request))]
    ;; Sync OIDC group memberships after successful login
    (log/infof "SYNC-GROUPS: result success=%s user=%s sync-enabled=%s groups=%s"
               (:success? result) (get-in result [:user :id]) (sso.settings/oidc-group-sync)
               (get-in result [:user-data :groups]))
    (when (and (:success? result)
               (:user result)
               (sso.settings/oidc-group-sync))
      (let [groups (get-in result [:user-data :groups])]
        (when (seq groups)
          (log/infof "SYNC-GROUPS: Syncing OIDC groups for user %s: %s" (get-in result [:user :id]) groups)
          (try
            (let [group-ids (oidc-groups->mb-group-ids groups)
                  all-mapped-ids (all-mapped-group-ids)]
              (sso/sync-group-memberships! (:user result) group-ids all-mapped-ids))
            (catch Throwable e
              (log/error e "Error syncing OIDC group memberships"))))))
    result))


;;; -------------------------------------------------- Group Sync Helpers --------------------------------------------------
