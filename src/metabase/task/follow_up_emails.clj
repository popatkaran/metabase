(ns metabase.task.follow-up-emails
  "Tasks which follow up with Metabase users."
  (:require [clj-time
             [coerce :as c]
             [core :as t]]
            [clojure.tools.logging :as log]
            [clojurewerkz.quartzite
             [jobs :as jobs]
             [triggers :as triggers]]
            [clojurewerkz.quartzite.schedule.cron :as cron]
            [metabase
             [email :as email]
             [public-settings :as public-settings]
             [task :as task]]
            [metabase.email.messages :as messages]
            [metabase.models
             [activity :refer [Activity]]
             [setting :as setting]
             [user :as user :refer [User]]
             [view-log :refer [ViewLog]]]
            [metabase.util.i18n :refer [trs]]
            [toucan.db :as db]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             send follow-up emails                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(setting/defsetting ^:private follow-up-email-sent
  ;; No need to i18n this as it's not user facing
  "Have we sent a follow up email to the instance admin?"
  :type      :boolean
  :default   false
  :internal? true)

(defn- send-follow-up-email!
  "Send an email to the instance admin following up on their experience with Metabase thus far."
  []
  ;; we need access to email AND the instance must be opted into anonymous tracking. Make sure email hasn't been sent yet
  (when (and (email/email-configured?)
             (public-settings/anon-tracking-enabled)
             (not (follow-up-email-sent)))
    ;; grab the oldest admins email address (likely the user who created this MB instance), that's who we'll send to
    ;; TODO - Does it make to send to this user instead of `(public-settings/admin-email)`?
    (when-let [admin (User :is_superuser true, :is_active true, {:order-by [:date_joined]})]
      (try
        (messages/send-follow-up-email! (:email admin) "follow-up")
        (catch Throwable e
          (log/error "Problem sending follow-up email:" e))
        (finally
          (follow-up-email-sent true))))))

(defn- instance-creation-timestamp
  "The date this Metabase instance was created. We use the `:date_joined` of the first `User` to determine this."
  ^java.sql.Timestamp []
  (db/select-one-field :date_joined User, {:order-by [[:date_joined :asc]]}))

;; this sends out a general 2 week email follow up email
(jobs/defjob FollowUpEmail [_]
  ;; if we've already sent the follow-up email then we are done
  (when-not (follow-up-email-sent)
    ;; figure out when we consider the instance created
    (when-let [instance-created (instance-creation-timestamp)]
      ;; we need to be 2+ weeks (14 days) from creation to send the follow up
      (when (< (* 14 24 60 60 1000)
               (- (System/currentTimeMillis) (.getTime instance-created)))
        (send-follow-up-email!)))))

(def ^:private follow-up-emails-job-key     "metabase.task.follow-up-emails.job")
(def ^:private follow-up-emails-trigger-key "metabase.task.follow-up-emails.trigger")

(defmethod task/init! ::SendFollowUpEmails [_]
  (let [job     (jobs/build
                 (jobs/of-type FollowUpEmail)
                 (jobs/with-identity (jobs/key follow-up-emails-job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key follow-up-emails-trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                   ;; run once a day
                   (cron/cron-schedule "0 0 12 * * ? *")))]
    (task/schedule-task! job trigger)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             send abandoment emails                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

(setting/defsetting ^:private abandonment-email-sent
  "Have we sent an abandonment email to the instance admin?"
  :type      :boolean
  :default   false
  :internal? true)

(defn- send-abandonment-email!
  "Send an email to the instance admin about why Metabase usage has died down."
  []
  ;; grab the oldest admins email address, that's who we'll send to
  (when-let [admin (User :is_superuser true, {:order-by [:date_joined]})]
    ;; inactive = no users created, no activity created, no dash/card views (past 7 days)
    (let [last-user     (c/from-sql-time (db/select-one-field :date_joined User,   {:order-by [[:date_joined :desc]]}))
          last-activity (c/from-sql-time (db/select-one-field :timestamp Activity, {:order-by [[:timestamp :desc]]}))
          last-view     (c/from-sql-time (db/select-one-field :timestamp ViewLog,  {:order-by [[:timestamp :desc]]}))
          two-weeks-ago (t/minus (t/now) (t/days 14))]
      (when (and (t/before? last-user two-weeks-ago)
                 (t/before? last-activity two-weeks-ago)
                 (t/before? last-view two-weeks-ago))
        (try
          (messages/send-follow-up-email! (:email admin) "abandon")
          (catch Throwable e
            (log/error e (trs "Problem sending abandonment email")))
          (finally
            (abandonment-email-sent true)))))))

;; this sends out an email any time after 30 days if the instance has stopped being used for 14 days
(jobs/defjob AbandonmentEmail [_]
  ;; if we've already sent the abandonment email then we are done
  (when-not (abandonment-email-sent)
    ;; figure out when we consider the instance created
    (when-let [instance-created (instance-creation-timestamp)]
      ;; we need to be 4+ weeks (30 days) from creation to send the follow up
      (when (< (* 30 24 60 60 1000)
               (- (System/currentTimeMillis) (.getTime instance-created)))
        ;; we need access to email AND the instance must be opted into anonymous tracking
        (when (and (email/email-configured?)
                   (public-settings/anon-tracking-enabled))
          (send-abandonment-email!))))))

(def ^:private abandonment-emails-job-key     "metabase.task.abandonment-emails.job")
(def ^:private abandonment-emails-trigger-key "metabase.task.abandonment-emails.trigger")

(defmethod task/init! ::SendAbandomentEmails [_]
  (let [job     (jobs/build
                 (jobs/of-type AbandonmentEmail)
                 (jobs/with-identity (jobs/key abandonment-emails-job-key)))
        trigger (triggers/build
                 (triggers/with-identity (triggers/key abandonment-emails-trigger-key))
                 (triggers/start-now)
                 (triggers/with-schedule
                   ;; run once a day
                   (cron/cron-schedule "0 0 12 * * ? *")))]
    (task/schedule-task! job trigger)))
