# AGENTS.md — Clojure + clojure-mcp + Mode-Driven TDD

## Project structure

The project is a monorepo with a module-style layout.

- `modules/pavlov` contains the pavlov's core, including its source (`/modules/pavlov/src`), tests (`/modules/pavlov/test`), and resources (`/modules/pavlov/resources`).
- `modules/pavlov-devtools` contains development tools for pavlov, including its source (`/modules/pavlov-devtools/src`), tests (`/modules/pavlov-devtools/test`), and resources (`/modules/pavlov-devtools/resources`).

### Development environment

The development environment loads the *both* `pavlov` and `pavlov-devtools` onto the classpath. Running the tests (via kaocha) will run the tests in *both* modules.

## Important to understand

It is important to understand behavioral programming and how pavlov implements it. A few items to read:

- ./README.md file
- ./doc/what-is-a-bthread.md

## Rules

- To find the type of an event, always use `tech.thomascothran.pavlov.event/type` function.
