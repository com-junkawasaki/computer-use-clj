(ns vultr-ip-allow
  "Add an IP to a Vultr API key's Access Control list via computer use.

     ANTHROPIC_API_KEY=… clojure -Sdeps '{:paths [\"src\" \"examples\"]
                  :deps {io.github.com-junkawasaki/langgraph-clj
                         {:git/tag \"v0.2.0\" :git/sha \"133740f\"}}}' \\
       -M -e \"(require 'vultr-ip-allow) (vultr-ip-allow/-main \\\"203.0.113.7\\\")\"

  Premises / guardrails:
  - A browser window with an ALREADY AUTHENTICATED my.vultr.com session
    must be frontmost. The agent never logs in: the system prompt
    forbids typing into any credential field, and if a login/2FA page
    appears the run ends with success=false asking the human to sign in.
  - The agent only ADDS one allowlist entry and saves; it never removes
    entries or touches other account settings.

  The action trail is datoms (computeruse.agent/log-schema), so the
  whole portal interaction is auditable after the fact."
  (:require [computeruse.macos :as macos]
            [computeruse.agent :as agent]
            [langchain.model :as model]
            [langchain.db :as db]))

(def system-prompt
  (str "You are a computer-use agent operating the user's macOS desktop.\n"
       "HARD RULES:\n"
       "- NEVER type into username, password, OTP or any credential field.\n"
       "- If a login, re-authentication or 2FA page appears, immediately call\n"
       "  `done` with success=false and text asking the human to sign in first.\n"
       "- Only ADD the single allowlist entry you were asked to add, then save.\n"
       "  Never delete or edit existing entries or other settings.\n"
       "Work in the frontmost browser window. Use cmd+l to focus the address\n"
       "bar. Take screenshots to verify every step before and after acting."))

(defn task [ip subnet-size]
  (str "In the frontmost browser, open https://my.vultr.com/settings/#settingsapi "
       "(Vultr → Account → API). In the 'Access Control' / allowed IPs section of "
       "the personal API key, add the IPv4 subnet " ip "/" subnet-size
       " to the allowlist and click the add/update button so it is saved. "
       "Confirm via screenshot that the new entry is listed, then call done "
       "with success=true and the entry you added."))

(defn -main [& [ip subnet]]
  (let [ip (or ip (System/getenv "VULTR_ALLOW_IP"))
        _ (assert ip "usage: (vultr-ip-allow/-main \"203.0.113.7\") or VULTR_ALLOW_IP env")
        subnet (or subnet "32")
        conn (db/create-conn agent/log-schema)
        {:keys [result steps]}
        (agent/run {:model (model/anthropic-model
                            {:api-key (System/getenv "ANTHROPIC_API_KEY")})
                    :computer (macos/macos-computer)
                    :system system-prompt
                    :task (task ip subnet)
                    :history-conn conn
                    :session-id (str "vultr-ip-" ip)
                    :max-steps 40})]
    (println "result:" result "| steps:" steps)
    (println "audit:"
             (sort-by first
                      (db/q '[:find ?step ?a
                              :where [?e :caction/step ?step]
                                     [?e :caction/action ?a]]
                            (db/db conn))))))
