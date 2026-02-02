# Designing Business Programs with Bthreads

This tutorial explains how to design business programs using the behavioral programming paradigm. It serves both as a tutorial for writing behavioral programs in pavlov, and as a guide to writing real-world applications with complex business logic.

The core challenge we will be addressing is that business processes are rife with implicit branching, cross-cutting rules, and environment-driven alternatives (credit checks, fraud flags, supervisor overrides). We will see how behavioral programming is a simple approach that lets us:

- Write business rules as isolated, composable units
- Create branching scenarios automatically
- Visualize those branching scenarios with a point-and-click UI
  + You can use this for visualizing your application as you are developing it, for demoing to a domain expert, or for letting an LLM explore the behavior of the your application (via `nav`)
- Run a model checker to verify your program.

You may even be convinced that model-checker-driven development is superior to REPL-driven development!

## Domain

Quality business applications need to express the domain. Imagine building a banking application, and using event storming to identify the following events that occur when a new customer wants to open an account.

- `:application-submitted` - the event that starts the process
- `:request-cip-verification` - verify the identity of the applicant. This can result in:
  + `:cip-verified`
  + `:cip-failed`, which should result in the `:application-declined` event
- `:ofac-screen-requested` - ensure the customer is not on the sanctions list
  + `:ofac-hit` should result in the `:application-declined` event
  + `:ofac-clear`
- `:initial-deposit-requested` after cip verification and clearance through OFAC

Of course, we know that the business rules are more complex, but this gives us a starting point.

Now we move on to our business rules. Some business rules pertain to order:

1. `:request-cip-verification` must occur (and either succeed or fail) before `:initial-deposit-requested`.
2. `:ofac-screen-requested` must be done before account creation.

Other business rules regard necessary relationships between events:

1. If `:cip-failed` happens, then `:application-declined` must follow
2. If `:ofac-hit` happens, then `:application-declined` must follow,

### Domain Rules Bthreads

Let's create our domain rules bthreads.

```clojure
(ns bank.rules
  (:require [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.viz.portal :as pvp]))

(defn make-request-cip-verification-bthread
  []
  (b/bids [{:wait-on #{:application-submitted}}
           {:request #{{:type :request-cip-verification}}}]))

(defn make-request-ofac-screen-bthread
  []
  (b/bids [{:wait-on #{:application-submitted}}
           {:request #{{:type :ofac-screen-requested}}}]))

...

(defn make-bthreads
  []
  {::request-cip-verification (make-request-initial-deposit-bthread)
   ...etc etc})
```

When an application is submitted, then we request CIP verification and OFAC screening. We need to decline the application if either returns a negative result:

```clojure
(defn make-cip-failure-rule-bthread
  []
  (b/on :cip-failed
        (constantly
         {:request #{{:type :application-declined}}})))

(defn make-ofac-hit-rule-bthread
  []
  (b/on :ofac-hit
        (constantly
         {:request #{{:type :application-declined}}})))
```

We can't forget to request our initial deposit:

```clojure
(defn make-request-initial-deposit-bthread
  []
  (b/bids [{:request #{{:type :initial-deposit-requested}}}]))
```

We'll add all these to `make-bthreads`.

But wait! Don't we have rules about when to request that deposit?

We do, but these rules should be decoupled. Some policies about when the initial deposit may be mandatory. Others may change. The rules about when the initial deposit should be requested should be able to change independently as policies and regulations change.

So we add separate rules:


```clojure
(defn make-block-deposit-until-cip-verified
  []
  (b/bids [{:block #{:initial-deposit-requested}
            :wait-on #{:cip-verified}}]))

(defn make-block-opening-until-ofac-cleared
  []
  (b/bids [{:block #{:initial-deposit-requested}
            :wait-on #{:ofac-clear}}]))
```

We *block* the request for an initial deposit until the CIP verification event and OFAC clearance event occurs. The bank policy could change to permit requesting the initial deposit before the OFAC screen clears, so long as the account is not opened first.

Note that we can change this business rule in one place, without complecting rules in a `cond` expression.

We need a few more business rules:

```clojure
(defn make-open-on-funding-bthread
  []
  (b/bids [{:wait-on #{:initial-deposit-paid}}
           {:request #{{:type :account-opened}}}]))
```

Now, we want to verify our system is correct. For that, we will need a few more bthreads.

### Environment Bthreads

Environment bthreads request events that come from the environment (i.e., from actions outside the system). This can go in an `environment.clj` file.

Crucially, this lets us introduce branches:

```clojure
(defn make-init-bthreads
  []
  (b/bids [{:request #{{:type :application-submitted}
                       {:type :initial-deposit-paid}}}]))

(defn make-environment-bthreads
  []
  ;; important - establishes one top-level branch in execution graph
  {::init-events (make-init-bthreads)

   ::handle-request-cip-verification
   (b/bids [{:wait-on #{:request-cip-verification}
            ;; important - the request establishes a branch
            {:request #{{:type :cip-failed}
                        {:type :cip-verified}}])
   ::handle-request-ofact-screening
   (b/bids ... etc etc)})
```

We request events that happen when the customer submits their application and when they pay the initial deposit.

Note that we are using these environment bthreads to *simulate* events; we would not use these bthreads outside of a test context.

By requesting *both* alternatives in an unordered collection, we indicate that either event can occur. This lets us use Pavlov's bthread explorer to click through the execution paths of our application.

### Safety Bthreads

Safety bthreads are used to detect things that should not happen.

For example, we might have a rule that says that an account should not be opened unless the ofac screen has cleared.

```clojure
(defn make-account-opening-requires-ofac-screening-bthread
  []
  (b/bids [{:wait-on #{:account-opened}}
           {:wait-on #{:ofac-clear}
            :request #{{:type :account-opened
                        :invariant-violated true}}}]))
```

This bthread can be read as:

- When the account is opened
- Try to open the account
- Until the OFAC screen clears.

The `:invariant-violated` property tells our model checker that this specific event should not be selected.

If the `:ofac-clear` event occurs, then this bthread will no longer request the account-opened event with an invariant violation. Another bthread may request the `account-opened` event, but (so long as that event does not have `:invariant-violated` set to true) the model checker will accept it as valid.

### Model Checking

We're going to wrap our bthreads into function calls to construct the domain, environment, and safety bthreads. Each namespace should define a `make-bthreads` function.

Now, let's see what our model checker has to say. If it returns `nil`, then no violation was found.

```clojure
(defn run-model
  []
  (-> {:bthreads (rules/make-bthreads) ;
       :environment-bthreads (environment/make-bthreads)
       :safety-bthreads (safety/make-bthreads)
       :check-deadlock? false}
     (check/check)))
```

On a failure, the model checker provides not only the path, but the state of the bthreads, which is very useful for debugging. (LLMs work great with this as well.)

To get our model to pass the check, we add another bthread and re-run the model checker. This becomes our iterative cycle: add safety properties, run the model checker, and the model checker will indicate the next bthread to be written.
