;; Copyright 2019 Timothy Dean
(ns morphe.core
  (:require [morphe.impl.defn])
  (:use-macros [morphe.core :only [defn alter-form prefix-form alter-bodies prefix-bodies]])
  (:refer-clojure :exclude [defn]))
