# Bthread Navigator

## User Story

In order to easily visualize possible paths of along which a behavioral program might execute
As a developer
I want to have a convenient point and click interface to walk back and forth, up and down through the tree of paths along with the program can execute

## Functional Specifications

Given a group of bthreads and an initial event
When I open the bthread navigator
Then I see a circle that says inside it "origin"
And I see :events that I can select in order for the behavioral program to take that path.

  | events                                  |
  | `{:type :leave-for-work, :time "7AM"}`  |
  | `{:type :leave-for-work, :time "8AM"}`  |
  | `{:type :stay-home}`                    |

When I select `{:type :leave-for-work, :time "7AM"}`
Then I see a branch off the origin
And I see on the other end of the branch a card with the event `{:type :leave-for-work, :time "7AM"}`
And I see the events as options related to that card

  | events                    |
  | `{:type :get-coffee}`     |
  | `{:type :park-at-office}` |

When I select `{:type :get-coffee}`
Then I see a branch off the `{:type :leave-for-work, :time "7AM"}` card
And that branch is connected to the `{:type :get-coffee}` card.
And I see the following events available for selection

  | events                    |
  | `{:type :park-at-office}` |

When I select the `{:type :stay-home}`
Then I see a new branch off the origin to a card entitled `{:type :stay-home}`
And off of the `{:type :stay-home}` card, I see two selectable options, `{:type :make-coffee}` and `{:type :open-laptop}`
And I continue to see the branch `{:type :leave-for-work, :time "7AM"}`


## Future add-ons

We will almost certainly add views for each node in the graph of the bthread states, the bids, what events are requested, waited on, or blocked (and by which bthread).

## Context

Behavioral programming includes first class model checking (see [here](./Model checking BP.pdf)). Program execution can be seen as a graph, and we can use DFS on it.

Pavlov generalizes this. The `tech.thomascothran.pavlov.search` namespace provides a `StateNavigator` protocol, which is used not only for depth first and breadth first search, but also programmatic navigation with clojure `nav` support. (see the namespace `tech.thomascothran.pavlov.nav`) and the associated tests.

The doc directory has two articles with more detail:

- `navigating-bprograms.md`
- `designing-business-programs-with-behavioral-threads.md`

There's a namespace, `tech.thomascothran.pavlov.viz.portal`, which uses the portal data inspector tool alongside

Some of this can be reused, other parts are inspiration.

## Steps

### Wireframe

We want to wireframe out what this could look like in html when all of the steps in the above scenario have been taken.

We don't want any actual functionality yet, just a static html page. No build process allowed!

#### Wireframe Styling Context

- Aim for a professional-but-approachable visual tone.
- Use a static, fixed viewport for the first pass; no pan/zoom yet.
- Lay out sample data (e.g., the scenarios above) manually; no live program state during the wireframe phase.
- Keep every explored branch visible simultaneously; evoke an "infinite canvas" even if the first draft is static.
- Represent the origin as a larger circle; subsequent nodes share a consistent card style distinct from the origin.
- Prefer CSS-driven curved connectors that communicate directionality, avoiding inline SVG for now.
- Styling stack guidance: start with Tailwind + DaisyUI for cards/typography and add custom CSS for graph layout and connectors. Future detail views (tables, accordions) can lean on DaisyUI components.


### Create jetty ring server and handler
