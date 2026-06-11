(ns computeruse.tool
  "The computer tool — Anthropic computer-use action vocabulary
  (screenshot / key / type / mouse_move / left_click / right_click /
  middle_click / double_click / left_click_drag* / scroll /
  cursor_position) dispatched onto an IComputer.

  Defined as an ordinary custom tool with an explicit input_schema, so
  it works through langchain.model's Anthropic adapter as-is. Hosts
  that prefer Anthropic's server-defined tool type
  ({:type \"computer_20250124\" …}) can send that wire format
  themselves and still dispatch results through `dispatch`.

  (* drag is mapped to mouse-move + click for v0.1.)"
  (:require [computeruse.computer :as c]))

(def actions
  ["screenshot" "key" "type" "mouse_move"
   "left_click" "right_click" "middle_click" "double_click"
   "scroll" "cursor_position"])

(defn dispatch
  "Executes one computer action map {:action .. :coordinate [x y]
  :text .. :scroll_direction .. :scroll_amount ..} on the computer.
  Returns the tool-result content (string, or whatever the host's
  screenshot returns)."
  [computer {:keys [action coordinate text scroll_direction scroll_amount]}]
  (let [[x y] coordinate]
    (case action
      "screenshot" (c/-screenshot computer)
      "key" (c/-key! computer text)
      "type" (c/-type! computer text)
      "mouse_move" (c/-mouse-move! computer x y)
      "left_click" (c/-click! computer :left x y)
      "right_click" (c/-click! computer :right x y)
      "middle_click" (c/-click! computer :middle x y)
      "double_click" (c/-click! computer :double x y)
      "scroll" (c/-scroll! computer x y
                           (keyword (or scroll_direction "down"))
                           (or scroll_amount 3))
      "cursor_position" (str (c/-cursor-position computer))
      (throw (ex-info "Unknown computer action" {:action action})))))

(defn computer-tool
  "langchain tool map over an IComputer.
  opts: {:width px :height px} — advertised display size."
  [computer & [{:keys [width height] :or {width 1280 height 800}}]]
  {:name "computer"
   :description
   (str "Control a computer with a " width "x" height " display. "
        "Take a screenshot to see the screen; click/type/scroll/press keys. "
        "Coordinates are [x, y] pixels from the top-left.")
   :schema {:type "object"
            :properties
            {:action {:type "string" :enum actions
                      :description "The action to perform."}
             :coordinate {:type "array" :items {:type "integer"}
                          :description "[x, y] for mouse actions."}
             :text {:type "string"
                    :description "Text to type, or key combo for `key` (e.g. ctrl+s, Return)."}
             :scroll_direction {:type "string" :enum ["up" "down" "left" "right"]}
             :scroll_amount {:type "integer"}}
            :required ["action"]}
   :fn (fn [args] (dispatch computer args))})
