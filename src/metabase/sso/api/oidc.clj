(ns metabase.sso.api.oidc
  "OIDC SSO routes for community edition.

   Handles:
   - GET /auth/sso/:key - Initiate OIDC flow
   - GET /auth/sso/:key/callback - Handle OIDC callback"
  (:require
   [metabase.api.macros :as api.macros]
   [metabase.sso.integrations.oidc :as oidc-integration]
   [metabase.util.log :as log]))

(set! *warn-on-reflection* true)

;; GET /auth/sso/:key
(api.macros/defendpoint :get "/:key"
  "Initiate OIDC SSO for a specific provider."
  [{provider-key :key} :- [:map [:key :string]]
   _query-params _body request]
  (try
    (oidc-integration/sso-initiate provider-key request)
    (catch Throwable e
      (log/error e "Error initiating OIDC SSO")
      (throw e))))

;; GET /auth/sso/:key/callback
(api.macros/defendpoint :get "/:key/callback"
  "OIDC callback for a specific provider."
  [{provider-key :key} :- [:map [:key :string]]
   _query-params _body request]
  (try
    (oidc-integration/sso-callback provider-key request)
    (catch Throwable e
      (log/error e "Error handling OIDC callback")
      (throw e))))

(def ^{:arglists '([request respond raise])} routes
  "`/auth/sso` OIDC routes."
  (api.macros/ns-handler *ns*))
