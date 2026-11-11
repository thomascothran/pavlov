# Pavlov Explorer UI candidates

Clickable mock-ups for step 1 of `../../010_pavlov-explorer.md`. Open
`index.html` in a browser; no server is needed.

- `a-trail.html`: local view only.
- `b-columns.html`: local view plus one step of lookahead.
- `c-map.html`: local view plus a map of the whole program.

The pages are static. `data.js` is generated from the real states and
transitions of three small programs by `gen/explorer_mock/gen.clj`:

    clojure -Sdeps '{:paths ["doc/design/010_pavlov-explorer/mockups/gen"]}' \
            -M -m explorer-mock.gen doc/design/010_pavlov-explorer/mockups/data.js

The mock-ups load fonts from Google Fonts and fall back to system fonts
offline. They are throwaway: nothing here is the implementation.
