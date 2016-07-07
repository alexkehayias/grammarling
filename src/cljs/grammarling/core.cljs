(ns grammarling.core
  (:require [instaparse.core :as insta]
            [instaparse.transform :as t]
            [reagent.core :as r]
            [cljsjs.codemirror]
            [cljsjs.codemirror.mode.css]
            [cljsjs.codemirror.mode.ebnf])
  (:require-macros [grammarling.config :refer [cljs-env]]))

(enable-console-print!)

(defn get-or-create-el-by-id
  "Return the element by id and a boolean of whether it was created or not"
  [id]
  (if-let [e (.getElementById js/document id)]
    [e false]
    [(doto (.createElement js/document "style")
       (aset "id" id))
     true]))

(defonce state (r/atom nil))

(defn update-preview [event]
  (swap! state assoc :text (.getValue event))
  nil)

(defn update-rules [event]
  (swap! state assoc :rules (.getValue event))
  nil)

(defn update-css
  "Updates the css in app state and injects css into the dom as a style tag"
  [event]
  (let [css (.getValue event)
        [el created?] (get-or-create-el-by-id "parse-styles")]
    ;; If it was created, add it to the body so the css can be evaluated
    (when created?
      (.appendChild (.-body js/document) el))
    ;; Replace the css in the style tag
    (aset el "innerText" css)
    ;; Update state with the new css
    (swap! state assoc :css css))
  ;; Return nil so we don't break event bubbling
  nil)

(defn default-transform
  "Returns a function for the given token that returns a hiccup element
   where the class is the name of the token"
  [token]
  (fn [val]
    [:div {:class (name token)} val]))

(defn- hiccup-transform
  [parse-tree]
  (if (and (sequential? parse-tree) (seq parse-tree))
    (if-let [transform (default-transform (first parse-tree))]
      (t/merge-meta
       (transform (map hiccup-transform (next parse-tree)))
       ;; Add in a unique key for react
       (assoc (meta parse-tree) :key (gensym)))
      (with-meta
        (into [(first parse-tree)]
              (map default-transform (next parse-tree)))
        (meta parse-tree)))
    parse-tree))

(defn react-transform
  "Takes a parse tree (or seq of parse-trees) and returns a hiccup
   sequence."
  [parse-tree]
  ;; Detect what kind of tree this is
  (cond
    ;; This is a hiccup tree-seq
    (and (vector? parse-tree) (keyword? (first parse-tree)))
    (hiccup-transform parse-tree)
    ;; This is either a sequence of parse results, or a tree
    ;; with a hidden root tag.
    (sequential? parse-tree)
    (t/map-preserving-meta react-transform parse-tree)
    ;; Pass failures through unchanged
    (instance? instaparse.gll.Failure parse-tree)
    parse-tree
    ;; Throw up
    :else
    (throw "Invalid parse-tree")))

(def parser-memo
  (memoize insta/parser))

(defn preview-render []
  [:div#preview
   {:class "Aligner-grid three"}
   [:div {:style {:height "100%" :overflow "auto"}}
    (let [{:keys [text rules]} @state
          parse-tree (insta/parse (parser-memo rules)
                                  (or text ""))]
      ;; If this is a parsing error it does not throw, it returns
      ;; an object so log it and return nil
      (if (instance? instaparse.gll.Failure parse-tree)
        (do (println parse-tree) nil)
        (react-transform parse-tree)))]])

(defn rules-input []
  (r/create-class
   {:component-did-mount (fn [& args]
                           (let [obj (.fromTextArea
                                      js/CodeMirror
                                      (.getElementById js/document "rules")
                                      #js {"lineNumbers" true
                                           "lineWrapping" true
                                           "theme" "monokai"
                                           "mode" "ebnf"})]
                             (.on obj "change" update-rules)))
    :reagent-render (fn []
                      [:textarea#rules
                       {:class "Aligner-grid three"
                        :style {:font-size "16px"}
                        :defaultValue (-> state deref :rules)}])}))

(defn text-input []
  (r/create-class
   {:component-did-mount (fn [& args]
                           (let [obj (.fromTextArea
                                      js/CodeMirror
                                      (.getElementById js/document "input")
                                      #js {"lineNumbers" true
                                           "lineWrapping" true
                                           "theme" "monokai"})]
                             (.on obj "change" update-preview)))
    :reagent-render (fn []
                      [:textarea#input
                       {:class "Aligner-grid three"
                        :defaultValue (-> state deref :text)}])}))

(defn css-input []
  (r/create-class
   {:component-did-mount (fn [& args]
                           (let [obj (.fromTextArea js/CodeMirror
                                                    (.getElementById js/document "styles")
                                                    #js {"lineNumbers" true
                                                         "lineWrapping" true
                                                         "theme" "monokai"})]
                             (.on obj "change" update-css)))
    :reagent-render (fn []
                      [:textarea#styles
                       {:class "Aligner-grid three"
                        :defaultValue (-> state deref :css)}])}))

(defn main [init-state]
  [:div {:class "Wrap"}
   [:div {:class "Aligner" :style {:height "100%" :width "100%"}}
    [text-input]
    [rules-input]
    [css-input]
    [preview-render]
    [:style {:id "parse-styles"} (:css init-state)]]])

(defn ^:export run [init]
  (r/render [main (reset! state init)]
            (js/document.getElementById "app"))
  ;; WARNING: this is gross interop
  ;; Append google analytics depending on the env
  (when (= (cljs-env :environment) "prod")
    (when-not (.getElementById js/document "analytics")
      (.appendChild
       js/document.body
       (doto (.createElement js/document "script")
         (aset "id" "analytics")
         (aset "innerHTML"
               (str "
  (function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','https://www.google-analytics.com/analytics.js','ga');

  ga('create', '" (cljs-env :google-analytics-id) "', 'auto');
  ga('send', 'pageview');
")))))))

(def init-text
  "#+title Grammar playground
#+author Alex Kehayias
* About the grammar playground
This is an interactive utility to help explore EBNF grammars
- Updates in real time
- Uses the ClojureScript port of Instaparse
- Check for parsing errors in the JS console
* How it works
The input area you are looking at is parsed into html by the grammer in the second input area. Each token is wrapped in a nested div element where the class is the name of the token. Styles are then applied using the third input area and finally rendered in the last area.

* Example of a simplified org-mode grammar
This is an example of some tokens in the example grammar.
** Headings can be nested
Content within heading is nested to the headline
** TODO Tasks can be captured by matching \"TODO\" or \"DONE\"
** DONE Tasks are denoted by \"DONE\"")

(def init-rules
  "<DOC> = preamble heading*
preamble = [title] [author]
title = <'#+title'> text EOL
author = <'#+author'> text EOL
heading = level heading-text content*
<EOL> = '\\n' | '\\r' | #'$'
<SPC> = ' '
level = #'\\*{1,}' SPC
heading-text = [status SPC] !status #'^.+'
text = #'^.+'
<content> = (EOL (EOL | heading | !level text))
todo = 'TODO'
done = 'DONE'
status = todo | done")

(def init-css
  ".preamble {
  margin: 20px 0 40px 0;
}

.title .text {
  font-size: 32px;
  font-weight: bold;
  text-align: center;
}

.author .text {
  text-transform: italicize;
  text-align: center;
}

.heading {
  background: #eee;
  padding: 10px 0 10px 10px;
  margin-bottom: 10px
}

.level {
  display: none;
}

.heading-text {
  display: inline-block;
  font-weight: bold;
}

.text {
  font-weight: normal;
  font-size: 16px;
  color: #1d1d1d;
}

.status {
  font-weight: bold;
  font-size: 16px;
  display: inline-block;
  margin-right: 4px;
  float: left;
}

.status .todo {
  color: white;
  background: red;
  padding: 4px 6px;
}

.status .done {
  color: white;
  background: green;
  padding: 4px 6px;

}

.list-bullet {
  display: inline-block;
  margin-right: 4px;
  font-weight: bold;
}

.list-text {
  display: inline-block;
}")

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  (run (or @state {:text init-text
                   :rules init-rules
                   :css init-css})))

(set! (.-onload js/window) on-js-reload)
