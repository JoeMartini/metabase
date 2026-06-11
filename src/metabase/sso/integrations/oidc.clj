(ns metabase.sso.integrations.oidc
  "OIDC SSO integration for community edition.

   Flow:
   1. User accesses GET /auth/sso/:key
   2. Metabase redirects to provider's authorization endpoint
   3. User authenticates with the IdP
   4. IdP redirects back to GET /auth/sso/:key/callback?code=...&state=...
   5. Metabase exchanges code for tokens and creates session"
  (:require
   [java-time.api :as t]
   [metabase.api.common :as api]
   [metabase.auth-identity.core :as auth-identity]
   [metabase.request.core :as request]
   [metabase.sso.common :as sso.common]
   [metabase.sso.core :as sso]
   [metabase.sso.oidc.state :as oidc.state]
   [metabase.sso.settings :as sso.settings]
   [metabase.system.core :as system]
   [metabase.util.i18n :refer [tru]]
   [metabase.util.log :as log]
   [ring.util.response :as response]))

(set! *warn-on-reflection* true)

(defn- get-redirect-uri
  "Generate the redirect URI for an OIDC provider callback."
  [provider-key]
  (str (system/site-url) "/auth/sso/" provider-key "/callback"))

(defn- check-sso-redirect
  "Validate that a redirect URL is safe. Only allows relative URLs or URLs on the same site."
  [redirect-url]
  (if (or (nil? redirect-url)
          (empty? redirect-url))
    "/"
    (if (or (not (string? redirect-url))
            (and (not (clojure.string/starts-with? redirect-url "/"))
                 (not (clojure.string/starts-with? redirect-url (system/site-url)))))
      (do
        (log/warnf "Potentially unsafe redirect URL '%s', defaulting to /" redirect-url)
        "/")
      redirect-url)))

(defn sso-initiate
  "Initiate the OIDC SSO flow for the given provider key."
  [provider-key request]
  (api/check-400 (sso.settings/oidc-enabled) "OIDC is not enabled")

  (let [provider-config (sso.settings/get-oidc-provider provider-key)]
    (when-not provider-config
      (throw (ex-info (tru "OIDC provider ''{0}'' not found" provider-key)
                      {:status-code 404})))
    (when-not (:enabled provider-config)
      (throw (ex-info (tru "OIDC provider ''{0}'' is not enabled" provider-key)
                      {:status-code 400}))))

  (let [redirect-url (let [redirect (get-in request [:params :redirect])]
                       (check-sso-redirect redirect))
        auth-result  (auth-identity/authenticate
                      :provider/oidc
                      (assoc request
                             :oidc-provider-key provider-key
                             :redirect-uri (get-redirect-uri provider-key)
                             :final-redirect redirect-url))]
    (if (= :redirect (:success? auth-result))
      (sso/wrap-oidc-redirect auth-result
                              request
                              (keyword (str "oidc-" provider-key))
                              redirect-url
                              {:browser-id (:browser-id request)})
      (throw (ex-info (or (:message auth-result) (tru "Failed to initiate OIDC authentication"))
                      {:status-code 500})))))

(defn sso-callback
  "Handle the OIDC callback for the given provider key."
  [provider-key {{:keys [code state]} :params, :as request}]
  (let [login-result (auth-identity/login!
                      :provider/oidc
                      (assoc request
                             :oidc-provider-key provider-key
                             :code code
                             :state state
                             :oidc-provider (keyword (str "oidc-" provider-key))
                             :redirect-uri (get-redirect-uri provider-key)
                             :device-info (request/device-info request)))]
    (if (:success? login-result)
      (let [final-redirect (or (:redirect-url login-result) "/")
            base-response  (-> (response/redirect final-redirect)
                               (sso/clear-oidc-state-cookie))]
        (log/infof "OIDC authentication successful for provider %s, user %s"
                   provider-key (get-in login-result [:user :email]))
        (if-let [session (:session login-result)]
          (request/set-session-cookies request
                                       base-response
                                       session
                                       (t/zoned-date-time (t/zone-id "GMT")))
          base-response))
      (let [error-msg (or (:message login-result) (tru "OIDC authentication failed"))]
        (log/errorf "OIDC authentication failed for provider %s: %s" provider-key error-msg)
        (throw (ex-info error-msg {:status-code 401}))))))
