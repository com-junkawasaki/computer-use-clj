# computer-use-clj

Anthropic-computer-use-style desktop automation agent in **portable
Clojure** — every namespace is `.cljc`, designed for
**Clojure-on-WASM hosts** (SCI, ClojureScript, GraalVM, kotoba-clj) as
well as the JVM. The desktop itself is an injected host capability;
the action history is persisted through a **Datomic API**.

Built on [langgraph-clj](https://github.com/com-junkawasaki/langgraph-clj)
/ [langchain-clj](https://github.com/com-junkawasaki/langchain-clj).
Sibling of [browser-use-clj](https://github.com/com-junkawasaki/browser-use-clj).

```
src/computeruse/
  computer.cljc  IComputer protocol (host capability) + mock virtual screen
  tool.cljc      the computer tool — Anthropic action vocabulary → protocol dispatch
  agent.cljc     sampling loop (langgraph StateGraph) + action log as datoms
```

## Design

- **Computer = injected host capability** — implement `IComputer`
  (xdotool/screencapture, a VNC sandbox, an OS-automation MCP).
  Screenshots are passed through untouched, so a real host can return
  Anthropic image content blocks while the bundled `mock-computer`
  returns a deterministic text rendering of a virtual screen.
- **Anthropic action vocabulary** — the `computer` tool speaks
  screenshot / key / type / mouse_move / left_click / right_click /
  middle_click / double_click / scroll / cursor_position, defined as a
  custom tool with an explicit `input_schema` (API-compatible without
  the server-defined tool type; hosts that want
  `{:type "computer_20250124" …}` can send that wire format and reuse
  `computeruse.tool/dispatch`).
- **Datomic API premise** — every action becomes a datom
  (`:caction/action`, `:caction/input`, …): "every click in session
  s1" is a Datalog query. Graph checkpoints (resume /
  human-in-the-loop) come from langgraph-clj.

## Quickstart

```clojure
(require '[computeruse.computer :as c]
         '[computeruse.agent :as agent]
         '[langchain.model :as model]
         '[langchain.db :as db])

;; host capability: real IComputer impl, or the mock virtual screen:
(def vm (c/mock-computer
         {:size [1280 800]
          :windows [{:title "Editor" :rect [0 0 800 600] :content ""}
                    {:title "Terminal" :rect [800 0 480 600] :content "$ "}]}))

(def conn (db/create-conn agent/log-schema))

(agent/run
 {:model (model/anthropic-model {:api-key … :http-fn host-fetch …})
  :computer (:computer vm)
  :display {:width 1280 :height 800}
  :task "Run make test in the terminal"
  :history-conn conn
  :session-id "s1"
  :max-steps 25})
;; => {:result "…" :done true :messages […] :steps n}

;; the audit trail is datoms:
(db/q '[:find ?step ?action
        :where [?e :caction/step ?step] [?e :caction/action ?action]]
      (db/db conn))
```

Extra tools (bash, editors, …) sit alongside the computer tool:

```clojure
(agent/run {:tools [my-bash-tool my-editor-tool] …})
```

## Mapping from upstream

See [docs/adr/0001-architecture.md](docs/adr/0001-architecture.md) for
the computer-use → computer-use-clj correspondence (action vocabulary,
sampling loop, tool wire format) and the host-capability split.

## Tests / example

```sh
clojure -M:test     # 4 tests, 20 assertions
clojure -Sdeps '{:paths ["src" "examples"]
                 :deps {io.github.com-junkawasaki/langgraph-clj
                        {:git/tag "v0.2.0" :git/sha "133740f"}}}' \
        -M -e "(require 'desktop-agent) (desktop-agent/-main)"
```

Workspace development against local checkouts: `clojure -M:dev:test`.
