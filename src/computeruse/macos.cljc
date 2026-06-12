(ns computeruse.macos
  "Real IComputer host capability for macOS (JVM only).

  Shells out to the OS toolchain:

    screenshot        screencapture -x  → sips resize → Anthropic image block
    key / type        osascript (System Events keystroke / key code)
    mouse / click     cliclick  (https://github.com/BluM/cliclick, `brew install cliclick`)
    cursor position   cliclick p

  Coordinate model: the model sees screenshots resized to :model-width
  (default 1280) pixels; incoming click coordinates are scaled back to
  display points before dispatch, so the agent can click what it sees.

  Permissions: the calling terminal needs macOS Screen Recording (for
  screencapture) and Accessibility (for cliclick / System Events).

  This namespace deliberately implements no credential handling — an
  agent run on top of it must never type secrets (see
  examples/vultr_ip_allow.clj for the guardrail prompt)."
  (:require [computeruse.computer :as c]
            [clojure.string :as str])
  #?(:clj (:import [java.util Base64]
                   [java.nio.file Files Paths])))

#?(:clj
   (do

(defn- sh [& args]
  (let [p (.start (ProcessBuilder. ^java.util.List (vec args)))
        out (slurp (.getInputStream p))
        code (.waitFor p)]
    (when-not (zero? code)
      (throw (ex-info (str "command failed: " (str/join " " args))
                      {:exit code :out out})))
    out))

(defn- display-points
  "Main display size in points, via the Finder desktop bounds."
  []
  (let [out (sh "osascript" "-e"
                "tell application \"Finder\" to get bounds of window of desktop")
        [_ _ w h] (map #(Long/parseLong (str/trim %)) (str/split out #","))]
    [w h]))

(defn- png-size [path]
  (let [out (sh "sips" "-g" "pixelWidth" "-g" "pixelHeight" path)
        nums (map #(Long/parseLong %) (re-seq #"\d+" (str/replace out #"(?s).*pixelWidth: (\d+).*pixelHeight: (\d+).*" "$1 $2")))]
    (vec (take 2 nums))))

(defn- b64-file [path]
  (.encodeToString (Base64/getEncoder)
                   (Files/readAllBytes (Paths/get path (make-array String 0)))))

(def ^:private key-codes
  ;; named (non-character) keys → macOS virtual key codes
  {"return" 36 "enter" 36 "tab" 48 "space" 49 "delete" 51 "backspace" 51
   "escape" 53 "esc" 53 "left" 123 "right" 124 "down" 125 "up" 126
   "home" 115 "end" 119 "pageup" 116 "page_up" 116 "pagedown" 121 "page_down" 121
   "f1" 122 "f2" 120 "f3" 99 "f4" 118 "f5" 96 "f6" 97})

(def ^:private modifiers
  {"cmd" "command down" "command" "command down" "super" "command down"
   "ctrl" "control down" "control" "control down"
   "alt" "option down" "option" "option down"
   "shift" "shift down"})

(defn- key-script
  "xdotool-style combo (\"ctrl+l\", \"Return\", \"cmd+shift+t\") → AppleScript."
  [combo]
  (let [parts (str/split (str/lower-case combo) #"\+")
        mods (keep modifiers (butlast parts))
        mods (if (and (= 1 (count parts)) (modifiers (first parts))) [] mods)
        k (last parts)
        using (when (seq mods) (str " using {" (str/join ", " mods) "}"))]
    (str "tell application \"System Events\" to "
         (if-let [code (key-codes k)]
           (str "key code " code using)
           (str "keystroke \"" k "\"" using)))))

(defn- escape-as [s]
  (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")))

(defn macos-computer
  "IComputer over the live macOS desktop. opts:
     :model-width  screenshot width sent to the model (default 1280)

  Returns the IComputer directly (no wrapping state — the desktop is
  the state)."
  [& [{:keys [model-width] :or {model-width 1280}}]]
  (let [scale (atom nil)             ; [points-per-model-px-x points-per-model-px-y]
        to-points (fn [x y]
                    (let [[sx sy] (or @scale [1 1])]
                      [(long (* x sx)) (long (* y sy))]))]
    (reify c/IComputer
      (-screenshot [_]
        (let [path (str "/tmp/cuse-" (System/nanoTime) ".png")]
          (sh "screencapture" "-x" "-t" "png" path)
          (sh "sips" "-Z" (str model-width) path)
          (let [[mw mh] (png-size path)
                [pw ph] (display-points)]
            (reset! scale [(/ pw (double mw)) (/ ph (double mh))])
            [{:type "image"
              :source {:type "base64" :media_type "image/png"
                       :data (b64-file path)}}])))
      (-key! [_ combo]
        (sh "osascript" "-e" (key-script combo))
        (str "Pressed " combo))
      (-type! [_ text]
        (sh "osascript" "-e"
            (str "tell application \"System Events\" to keystroke \""
                 (escape-as text) "\""))
        (str "Typed " (pr-str text)))
      (-mouse-move! [_ x y]
        (let [[px py] (to-points x y)]
          (sh "cliclick" (str "m:" px "," py))
          (str "Moved to [" px " " py "]")))
      (-click! [_ button x y]
        (let [[px py] (to-points x y)
              cmd (case button
                    :left "c" :right "rc" :double "dc" :middle "c")]
          (sh "cliclick" (str cmd ":" px "," py))
          (str (name button) " click at [" px " " py "]")))
      (-scroll! [_ x y direction amount]
        ;; cliclick has no wheel verb — move then PageUp/PageDown, which is
        ;; what browser/portal pages need in practice.
        (let [[px py] (to-points x y)]
          (sh "cliclick" (str "m:" px "," py))
          (dotimes [_ (max 1 (long (/ (or amount 3) 3)))]
            (sh "osascript" "-e"
                (key-script (if (= direction :up) "pageup" "pagedown"))))
          (str "Scrolled " (name direction))))
      (-cursor-position [_]
        (let [out (sh "cliclick" "p")
              [x y] (map #(Long/parseLong %) (re-seq #"\d+" out))]
          [x y])))))

))
