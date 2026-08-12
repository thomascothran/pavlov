# Pavlov AI

This design doc lays out our options for using pavlov with LLMs.

## Objective
LLM agents should be "just another bthread" in a bprogram.

## Problem
How do we implement the loop?

For a super simple example:

1. Bthread is called with a `:user/message` event saying: "Hey, check my email and see if the boss emailed me"
  + When the bthread registers, it can *declare* which messages it is interested it. This is just a wait-on!
2. The LLM needs to know how to get the list of emails.
  + The bprogram provides a `:email/list` event that, when requested, returns the list
  + But how does the LLM know about this event?
    - Is it translated into a tool call?
    - Does the LLM return structured json, which are the events it can request. If so, how does it know about what events it can request?

### Out of Scope

What is out of scope for this problem statement is how the agent bthread interacts with the LLM api. That is done via regular bprogram events, where a request is emitted and there's a wait on the response or an error.

Our concern here is more specific: how does the LLM know what it can do? How is that intent (e.g., to get a list of emails) communicated? Ultimately these will be regular bids in our bprogram, but the question is how we get there.

## Options

### Option A - Structured JSON

We can do something like the following.

The first message tells the agent to return a list of events to request. (Should we allow it to wait-on events? Block them? I'm not sure)

```json
   {
     "messages": [
       {
         "role": "system",
         "content": "You are a helpful assistant. You may either return a final
 answer or request available actions. Available actions: email/list - gets a list
 of emails. Arguments schema:
 {\"type\":\"object\",\"properties\":{\"lookback\":{\"type\":\"string\"}},\"requi
 red\":[\"lookback\"]}. Always return JSON."
       },
       {
         "role": "user",
         "content": "Hey, check my email and see if the boss emailed me"
       }
     ],
     "response_format": {
       "type": "json_schema",
       "json_schema": {
         // oneOf json schema here
       }
     }
    }
```

The LLM replies with:

```json
  {
    "kind": "actions",
    "actions": [
      {
        "action": "email/list",
        "arguments": {
          "lookback": "24 hours"
        }
      }
    ]
  }
```

Then the bthread feeds it back into the system

```json
   {
     "messages": [
       {
         "role": "system",
         "content": "You are a helpful assistant. You may either return a final
 answer or request available actions. Available actions: email/list - gets a list
 of emails. Arguments schema: ... Always return JSON."
       },
       {
         "role": "user",
         "content": "Hey, check my email and see if the boss emailed me"
       },
       {
         "role": "assistant",
         "content":
 "{\"kind\":\"actions\",\"actions\":[{\"action\":\"email/list\",\"arguments\":{\"
 lookback\":\"24 hours\"}}]}"
       },
       {
         "role": "user",
         "content":
 "{\"kind\":\"action_results\",\"source\":\"pavlov/environment\",\"results\":[{\"
 action\":\"email/list\",\"status\":\"success\",\"result\":[{\"title\":\"where's
 my TPS report\",\"from\":\"boss\"}]}]}"
       }
     ]
   }
 ```

### Option A(i) - LLM selects any available request, wait, block event
For example, if `:x` is the request event type and `:y` is the response event type, the LLM needs to know that because it has requested `:x` it then needs to wait for `:y`. This may introduce more loops.

### Option A(ii) - Action specs wait programmatically
We might have an action spec that is defined as something like:

```clojure
{:action/name :email/list
 :description "List recent emails"

 :request/type :email/list
 :request/schema [:map
                  [:lookback :string]]

 :success/type :email/list-succeeded
 :success/schema [:map
                  [:emails
                   [:vector
                    [:map
                     [:from :string]
                     [:title :string]]]]]

 :failure/type :email/list-failed
 :failure/schema [:map
                  [:error :string]]}
```

Then we could have a multimethod that gives the json schema of the request and response and more importantly the bthread knows *programmatically* to wait on the response.

Note that we don't want to force the user to use malli. So we have a multimethod namespace that looks like this:

```clojure
(defmulti ->json-schema type)
(defmulti validate type)
(defmulti explain type)
(defmulti decode type)
(defmulti encode type)
```

And an optional namespace that when required extends this to malli.

```clojure
(ns pavlov.ai.schema.malli
  (:require [malli.core :as m]
            [malli.json-schema :as mjs]
            [pavlov.ai.schema :as schema]))

(defmethod schema/->json-schema clojure.lang.IPersistentVector
  [malli-schema]
  (mjs/transform malli-schema))

(defmethod schema/validate clojure.lang.IPersistentVector
  [malli-schema value]
  (m/validate malli-schema value))
```

### Option B - Tool Calls

The original spike used tool calls. Models are after all trained on tool calls. They have model tool call/result id correlations, etc. But in our case, we're tied into the tool calling standards, and we can avoid that. Plus models are good at returning JSON.

Native tool calls may still be useful as a progressive-disclosure interface over Pavlov’s action/skill catalog.
