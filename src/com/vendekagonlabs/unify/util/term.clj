;; Copyright 2023 Vendekagon Labs. All Rights Reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns com.vendekagonlabs.unify.util.term
  "Small ANSI terminal helpers shared by the CLI banner and the progress/log
  coloring: 256-color SGR codes, a brand-ish palette, and color-capability
  detection so we never dump raw escape sequences into a piped/redirected
  output stream (a CI log, `unify prepare > out.txt`, etc.)."
  (:require [clojure.string :as str]))

(defn color-enabled?
  "True when stdout looks like a real interactive terminal that supports
  ANSI color, and the user hasn't opted out via NO_COLOR (see
  https://no-color.org). Checked live (not cached) since it's cheap and some
  callers care about a freshly-redirected stream."
  []
  (and (nil? (System/getenv "NO_COLOR"))
       (some? (System/console))))

(defn- sgr
  [& codes]
  (str "\u001b[" (str/join ";" codes) "m"))

(def reset (sgr 0))

(defn fg [n] (sgr "38" "5" (str n)))
(defn bg [n] (sgr "48" "5" (str n)))
(def bold (sgr 1))
(def dim (sgr 2))

;; Vendekagon Labs brand-ish palette (xterm 256-color approximations of the
;; logo: a dark teal sphere, white monogram, warm copper accent).
(def teal 23)
(def white 15)
(def copper 173)

;; status colors, nextflow-esque
(def info 44)
(def warn 214)
(def error 203)
(def done 42)
(def muted 244)

(defn paint
  "Wraps s in the given SGR code string(s) (each a plain code fragment,
  e.g. from fg/bg/bold), resetting after -- only when color is enabled,
  otherwise returns s unchanged. `enabled?` is threaded through explicitly
  (rather than re-checked here) so a caller doing many small paints across a
  render pass only pays for the syscall/env lookup once per pass."
  [enabled? codes s]
  (if enabled?
    (str (apply str codes) s reset)
    s))
