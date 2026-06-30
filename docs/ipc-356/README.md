# IPC-D-356A — Bare-Board Electrical Test Netlist Format

Reference material for adding IPC-356 (IPC-D-356A) support to delta-gerber. IPC-D-356A is the
standard, ASCII, 80-column-per-record netlist format used to transmit a board's connectivity and
test-point data for bare-board electrical test. It is the format delta-gerber would *read* to
verify an extracted netlist, and *write* to hand connectivity to a fabricator.

## A note on sources

The **official IPC-D-356A standard** is copyrighted and sold by IPC (Association Connecting
Electronics Industries) — it is **not** freely redistributable, so it is not included here. Buy it
from [ipc.org](https://www.ipc.org/) if you need the authoritative text.

The format itself (record structure and column layout) is a functional specification and is widely
documented in free, third-party references. The two below are sufficient to implement a correct
reader/writer:

| File | Pages | What it covers |
|------|-------|----------------|
| [`ipc-d-356a-netlist-format-an40.md`](ipc-d-356a-netlist-format-an40.md) | 23 | **Field-by-field column definitions** for every record type — the implementation reference. (App-note "AN40".) |
| [`ipc-d-356-simplified.md`](ipc-d-356-simplified.md) | 6 | Plain-language overview of the format and its purpose. ("IPC-D-356 Simplified", DownStream Technologies.) |

Each `.md` was converted from the corresponding PDF (kept alongside it) with
[liteparse](https://github.com/run-llama/liteparse), matching the conversion used for the Gerber
spec in [`../gerber-spec-chapters`](../gerber-spec-chapters).

## Quick orientation for implementers

- **Records are fixed-width, 80 columns, ASCII.** A record's meaning is set by the 3-digit
  operation code in columns 1-3.
- **Header** lines start with `P` (parameters: `UNITS` `CUST`/`SI`, `JOB`, `NNAME` for long net
  names, image sections like `P IMAGE PRIMARY`); comment lines start with `C`.
- **Units**: coordinates and sizes are in **0.0001 inch** (`CUST`) or **0.001 mm** (`SI`) — note
  delta-gerber normalizes everything to mm, so an IPC reader must convert on the unit declared in
  the header. Coordinates use `X[±]nnnnnnY[±]nnnnnn`, feature size `X..Y..`, rotation `Rnnn`.
- **Key operation codes** (column 1-3):

  | Code | Meaning |
  |------|---------|
  | `317` | Through-hole feature / point |
  | `327` | Surface-mount (SMD) feature |
  | `367` | Non-plated tooling hole |
  | `307` | Blind or buried via |
  | `378` / `078` | Conductor segment data / continuation |
  | `379` / `079` | Net adjacency (possible-short) list / continuation |
  | `099` | Test-point location (continuation of a 3x7 record) |
  | `088` | Solder-mask clearance of the previous feature |
  | `389` / `089` | Board/panel outline / continuation |
  | `390` | Non-test feature (fiducial, target, marking) |
  | `999` | End of job |

  A leading `0` (e.g. `017`, `027`) marks a **continuation** of the previous record.
- **Key fields of a 3x7 test record**: net name (cols 4-17), reference designator + pin
  (cols 21-32, `VIA` allowed), hole definition (cols 33-38: `D`, diameter, `P`/`U`), test-point
  access (cols 39-41: `A00` both sides / `A01` primary / `A0n`), location (cols 42-57),
  feature size + rotation (cols 58-71), solder-mask (cols 73-74).

See the AN40 reference for the exact, column-precise definitions of every field and record type.
