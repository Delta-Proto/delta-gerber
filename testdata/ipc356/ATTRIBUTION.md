# IPC-D-356A test fixtures

## Synthetic (authored for this repo)

- `synthetic-cust.ipc` — hand-built `CUST` (0.0001 inch) fixture exercising every supported
  record type: `317`/`327`/`367`/`307` test points, `017` continuation hole, `099` test-point
  location, `088` solder-mask clearance, `378`/`078` conductor, `379`/`079` adjacency,
  `389`/`089` outlines, `P NNAME` alias, `N/C` and mid-net points.
- `synthetic-si.ipc` — small `SI` (0.001 mm) fixture for verifying metric unit conversion.

## Real-world fixtures (vendored from gerbonara)

Fetched from the [gerbonara](https://github.com/jaseg/gerbonara) test suite to verify the parser
against real CAM output. Each retains its original upstream license:

- `minnowmax-revA1.ipc` — Cadence Allegro IPC-D-356A export for the **MinnowBoard Max** (rev A1).
  Source: gerbonara `tests/resources/allegro-2/MinnowMax_RevA1_IPC356A.ipc`. The MinnowBoard Max
  design files are released by CircuitCo LLC under
  [CC-BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).
- `eagle-ipc-d-356.ipc` — EAGLE 7.1 IPC-D-356 export (small demo board).
  Source: gerbonara `tests/resources/ipc-d-356.ipc`.
