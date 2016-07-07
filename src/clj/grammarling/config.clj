(ns grammarling.config)

(def env
  {:environment (System/getenv "ENVIRONMENT")
   :google-analytics-id (System/getenv "GOOGLE_ANALYTICS_ID")})

(defmacro cljs-env
  "Returns the environment variable for the given keyword"
  ([kw] (get env kw))
  ([kw default] (get env kw default)))
