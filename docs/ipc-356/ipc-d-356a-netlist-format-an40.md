# IPC-D-356A NETLIST FORMAT

## Definitions

Every line (record) consists of 80 columns (characters). Records cannot exceed 80 columns.

## Organization of the IPC-D-356A File

IPC-D-356A are organized in a specific sequence which represent logical groupings of data to simplify the task of importing or exporting test data. Not all record structures are required since some data providers and receivers do not require or are not in a position to provide all the possible data sections, but the sequencing and marking of sections are both required. The sequence an content of the sections is as follows :

##### · Header

The header contains parameters related to the entire data set. The header must start with the "JOB" parameter and be followed by CODE, UNITS, TITLE, NUM, REV and VER.

##### · IMAGE PRIMARY

This section contains all the data associated with the Primary image as well as the test channel assignments for additional images and/or the test channel assignment for multiple tests of the same image. Sequencing of this subsection is as follows :

- The start of the section must be indicated with "P IMAGE PRIMARY"
- NNAME section for long net names found in the netlist , sorted by net.
- Netlist section including the Standard Electrical Test Records and Specified Test Point Data for the primary and any

additional images.

- Primary image net conductor segment data, sorted by net
- Primary image Board Outline Data
- Primary Image Resistor or Capacitor data, sorted by net

##### · IMAGE 2

This section contains the offset data which should be applied to the primary image to arrive at the orientation for image 2. The start of this section must be indicated by the Parameter "P IMAGE 2"

##### · IMAGE NNNN

This section contains the offset data which should be applied to the primary image to arrive at the orientation for image NNNN. The start of this section must be indicated by the Parameter "P IMAGE NNNN"

##### · IMAGE PANEL

This section must contain ;

- NNAME sectionfor long net names, sorted by net.
- Netlist section including the Standard Electrical Test Records and Specified Test Point Data.
- Panel image net conductor segment data, sorted by net
- Panel Board Outline data
- Panel Adjacency data, sorted by net
- Panel image Resistor or Capacitor data

##### · End of File Marker

The end of file is defined by the code 999.

AN40 Page 1

-----

## Sections

### HEADER INFORMATION

The header information always starts with a "P" in column 1 and two blanks in columns 2-3 , followed by the parameter designation in columns 4-7 , two blanks in columns 8-9 and the value of the parameter in column 10-72.

###### Parameter Description

| JOB | the name of the job |
|---|---|
| CODE | Indicate a switch to native language character set for comment records and the TITLE, NUM, and REV parameter records |

UNITS Units of measurement :

| SI | Metric |
|---|---|
| CUST 0 or CUST | Inches and degrees |
| CUST 1 | Millimeters and degrees |
| CUST 2 | Inches and radians |

Only one UNITS parameter is allowed in the file.

| TITLE | title of the data defined in this file |
|---|---|
| NUM | part number of the data |
| REV | revision number of the data |
| VER | The version of IPC-D-356 (either IPC-D-356 or IPC-D-356A) |
| IMAGE | Parameter |
| The parameter "IMAGE" in | columns 4-8 preceded by a "P" and two spaces in columns 1-3 indicates the start of a data |
| section within a file. | Following a space in column 9, the keywords PRIMARY , PANEL or an integer (NNNN) for an image |
| number are allowed arguments | for this parameter. |

###### Parameter Description

PRIMARY Indicates the start of the primary board data records. Note that PRIMARY occupies columns

10 through 16. This section contains the primary netlist information as well as the tester channel assignment for the primary and multi-up images (stepped boards) within a tested panel. NNNN Indicates the start of the offset data which should be applied to the primary image data to

arrive at the orientation for images 2 through NNNN. Up to 9999 images are allowed and the field should be filled as an integer starting in column 10. PANEL Indicates the start of the panel data records as opposed to primary or stepped image data

records. Panel data may include coupon data to be tested such as strip lines, registration monitors , etc … . Panel Data may also include non-test data for the panel or the sub-panel such as board outline data, non-plated tooling holes or fiducials.

-----

### Other Parameters

| Parameter | Description |
|---|---|
| P REMOVED_CONDUCTORS | Indicates the nets for which the conductor segment data has been omitted in order to reduce the file size. Column 19-21 must contain "L" , followed by the layer number on which the conductors are omitted. The layer number must contain a preceding 0 for layer numbers < 10. Columns 27 - 40 must contain must contain the 14 character net name for which conductors have been omitted. If conductor segment data is removed from multiple layers of a net , a parameter statement for each layer needs to be added. If conductor segment data has been removed from more than one net, a parameter statement for each net needs to be added. The REMOVED_CONDUCTORS parameter has to immediately precede the Net Conductor Segment data |
| P NNAME | Identifier or alias for a long net name that would not otherwise fit in the 14 character space allotted in the "General Test Record". Columns 9-13 contain the alias, while columns 15-72 are reserved for the user name assigned in the source data. Only one NNAME is allowed per long Net Name. Both names have to be left-justified. |

### Comments

Every record that starts with a "C" in column one is regarded as a comment record. Columns 2-3 must be left blank and columns 4-72 can be used for the comment text.

-----

### STANDARD ELECTRICAL TEST RECORDS

Every record consists of a line of 80 characters , referred to as columns.

###### ALLOWED OPERATION CODES (columns 1-3)

317 Through hole. Alternatively it can represent a feature and through hole at a point. 017 Continuation record that defines a through hole associated with the previous record 367 Non-plated tooling hole 327 Surface mount feature 027 Continuation record that defines a through hole associated with the previous record 099 Test point location of the feature described in the previous record 088 Solder mask clearance of the feature described in the previous record 307 Blind or buried via 309 Image 2 through NNNN offset data 370 In-board resistor, capacitor or inductor 070 Continuation of In-board resistor, capacitor or inductor 378 Conductor segment data 078 Continuation record of conductor segment data 379 Adjacency data record 079 Continuation of adjacency data record 380 On-board resistor, capacitor or inductor 080 Continuation of on-board resistor, capacitor or inductor 389 Board, panel or sub-panel , scoring or other fabrication outline data 089 Continuation of outline data 390 Non-test feature such as fiducials , targets , test status marking location , etc … 090 Reference for high-voltage isolation , impedance and other specified tests 999 End of Job data file

###### Signal Name Identification Field (columns 4-17)

Columns 4-17 = Net Name / Node Number (no space allowed). If the net name is longer than 14 chars, then

the parameter NNAME must be used in the P IMAGE PRIMARY section. N/C (in columns 4-6) = single point net / Isolated point

###### Columns 18-20

Should be left blank

###### Component Identifier Field (columns 21-32)

| Columns 21-26 | = Reference Designator (Device nomenclature). VIA may be entered if the point is a via. |
|---|---|
| Column 27 | = "-" A Dash to separate the reference designator from pin identifier |
| Columns 28-31 | = The pin identifier. (Pin Number) |
| Column 32 | May contain M to indicate a Mid Net point. Other wise left blank. |

-----

| Hole Definition Field (columns | 33-38) |
|---|---|
| Column 33 | = "D" Drilled Hole Identifier (only allowed is column 2 is "1" or "6) . Left blank if not drilled |
| Columns 34-37 | = The Hole diameter in 0.0001 Inch or 0.001 mm. |
| Column 38 | = "P" Indicates Plated Through hole = "U" Indicates Unplated Through hole |
|  |  |
| Test Point Access Field | (columns 39-41) |
| Column 39 | = "A" Access Code |
| Columns 40-41 | = "00" Test point is accessible from both sides (PTH) = "01" Test point is accessible from side 1 (Primary side) = "0n" Test pont is accesible from side "n" where "n" is usually the last outer layer |
|  |  |
| Test Point Location Field | (columns 42-57) |
| Column 42 | = Shall contain "X" to specify X location |
| Column 43 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 44-49 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 50 | = Shall contain "Y" to specify the Y location |
| Column 51 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 52-57 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
|  |  |
| Feature Size Field (columns | 58-71) |
| Column 58 | shall contain an "X" |
| Columns 59-62 | X - dimension in 0.0001 Inch or 0.001 mm |
| Column 63 | shall contain "Y" |
| Columns 64-67 | Y - dimension in 0.0001 Inch or 0.001 mm |
| Column 68 | shall contain R to signify Counter Clockwise Rotation of feature |
| Columns 69-71 | shall contain 3 digits to represent rotation (000-360) |
| Column 72 | Is unassigned and should be left blank |
|  |  |
| Soldermask Field (columns | 73-74) |
| Column 73 | Shall contain "S" to signify soldermask information |
| Column 74 | = 0 specifies no solder mask = 1 specifies primary side soldermask = 2 specifies secondary side soldermask = 3 specifies both sides soldermasked |

### EXTENDED ELECTRICAL TEST RECORD STRUCTURE

-----

The extended electrical test record structure contains additional information to assist in electrical test , repair and analysis. These records actually describe the boards design , using the Standard Electrical Test Records and the Extended Electrical Test Records.

#### Conductors

| columns 1-3 | Must contain "378" for the start of a new conductor or "078" for the continuation of a conductor specified in the previous record |
|---|---|
| columns 4-17 | = Net Name / Node Number (no space allowed). If the net name is longer than 14 chars, then the parameter NNAME must be used in the P IMAGE PRIMARY section. |
| column 18 | must be blank |
| column 19 | must contain "L" |
| columns 20-21 | 2 digit code defining the layer number (preceding 0 id layer number <10) |
| column 22 | must be blank |

The rest of the record contains the drawing (aperture) size of the conductor and the coordinates. The aperture size can be presented in 2 ways. If only the X size is defined, then the aperture is assumed to be round and the number following it is the diameter in the units defined in the header (eg if UNITS are set SI, then X150 would be a round aperture of 0.15mm). If X and Y size is defined then the aperture is assumed to be square or rectangular. Leading zero suppression is allowed The coordinate fields are delimited with a space or an asterisk (\*). A space means that the previous coordinate is start coordinate for the next segment. An asterisk means that the following coordinate is not connected to the previous coordinate but that the following coordinate is a new start coordinate. All coordinates , except , the first one can be modal (if the next coordinate has the same X or Y coordinate as the previous one, then the X or Y coordinate does not have to be repeated.

###### EXAMPLE:

| 378NET1 | L01 X1234Y1234 X+123456Y+123456 X+123456Y+123456 |
|---|---|
| 078 X+123456Y123456 | X+123456Y+123456 X+123456Y+123456 X+123456Y+123456 |
| 378GROUND | L01 X150 X40000Y250000 Y275000 X50000*X275000Y300000 X300000 |

#### ADJACENCY DATA

Adjacency is a list of nets that could possibly be shorted. Typically the criteria for adjacency is based on a minimum feature separation distance. Net adjacency information is used to reduce isolation testing on flying probe test systems and other test coverage for efficiency purposes. Note that double entries are not necessary. If the adjacency information for "net1" contains the net "power" , then the adjacency information of the net power does not need to include the "net1". The record needs to start with operation code 379 (columns 1-3. Continuation records of the same adjacency list need to start with code 079. After the operation code the Initial net name is defined. This name is max 14 characters, but not all 14 characters need to be used. Space characters are not allowed in the net name. Long signal name need to be handled with their alias. Every field is separate from the previous field with a space. The fields must contain the net names that are adjacent to the initial net.

###### EXAMPLE

```
379NET1 NET23 NET2 NET45 PG12 12VOLT NET_IC12 NET44 NET123 NET678 NET32
079VOLTAGE_POWER NETSSA23 WEDRFRGR7 SDSDFE34 NET456
```

-----

#### TEST POINT LOCATION

This defines a data structure that will define the actual location of the test point, and its relationship to the tester grid (the assigned grid or channel location). This record does not stand alone but is a continuation of a Standard Electrical Tester record.

| columns 1-3 | Must contain code 099 |
|---|---|
| columns 4 - 17 | = Net Name / Node Number (no space allowed). If the net name is longer than 14 chars, then the parameter NNAME must be used in the P IMAGE PRIMARY section. |
| columns 18-21 | Must be left blank |
| columns 22-37 | Assigned location field (see below) |
| column 38 | Must be left blank |
| columns 39-41 | Test point access field. If the actual test side is determined by the test fixture , the test access side must be described as either T01 or TNN, where NN is the test layer number. If the actual test side is not determined, such as with flying probe test, "T00" must be specified. |
| Column 42 | = Shall contain "X" to specify X location |
| Column 43 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 44-49 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm of the testpoint on the board. Leading zeroes may be blank. |
| Column 50 | = Shall contain "Y" to specify the Y location |
| Column 51 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 52-57 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm of the testpoint on the board. Leading zeroes may be blank. |
| columns 58 | must be left blank |
| column 59 | can contain "Z" if a z axis location has to be specified |
| column 60 | = "+" or "-" or blank to specify the sign (blank = "+") |
| columns 61 - 66 | = Shall contain six digits representing the Z location in 0.0001 Inch or 0.001mm of the testpoint on the board. Leading zeroes may be blank. A positive Z axis indicates the distance into the board, a negative Z axis indicates a distance away from the board, from the side indicated. |
| columns 68 - 72 | If column 68 contains an "I", columns 69-72 must contain a 4-digit image number matching the parameter P IMAGE NNN that the test point is associated with. This allows the representation of multiple-up image test for a single part number without greatly increasing the data size. No zero suppression allowed. If the data set represents a single image test, columns 68-72 are optional. If the multiple images are included in the data set, the number 0001 must be used in columns 69-72 for the Primary Image. |

-----

##### ASSIGNED LOCATION FIELD (Columns 22 - 37)

This field contains the assigned location on the tester. This location can be represented in a number of ways and is dependent on the tester being used. Column 22 is used to differentiate between the possible assigned location types. If required , more than one type of assigned location may be used in a file

| Grid based assignment |  |
|---|---|
| Channel based assignment (Dedicated fixture) |  |
| Block and Pin Number assignment |  |
| Flying Probe assignment |  |
| Shorting Blocks |  |
| Row and Column assignment |  |

###### EXAMPLES

```
327NET1
099NET1
327NET1
```

In case of Grid XY coordinates, the units must be in the same units as the test point data. This will allow non 100 mil grids to be represented. Column 22 must contain "X" to indicate grid based assignment Column 23 contains "+" or "-" or blank to specify the sign (blank="+") Columns 24-29 contain a 6 digit representation of the X-location Column 30 must contain "Y" Column 31 contains "+" or "-" or blank to specify the sign (blank="+") Columns 32-37 contain a 6 digit representation of the Y-location Column 22 must contain a "C" to indicate a channel based assigned location Columns 23-32 must contain a 10 character alphanumeric representation of the channel number Column 22 must contain a "B" to indicate Block and Pin number based assignment. Columns 23-29 must contain a 7 character alphanumeric representation of the block number Column 30 must contain "P" Columns 31-37 must contain a 7 character alphanumeric representation of the pin number as associated with the block Column 22 must contain "P" to indicate a Flying probe based test. Columns 23-37 must be left blank, as the probe location will be taken from the Test Point Location field in columns 42-57. If the access side provided to the prober is "both", columns 39-41 should include the characters "T00" Column 22 must contain "S" to indicate a Shorting Block based test Columns 23-36 must contain a 4 digit representation of the shorting block number. Shorting block number 0 is invalid and may not be used. Column 22 must contain "R" Columns 23-29 must contain a 7-digit representation of the row number Column 30 must contain a "C" Columns 31-37 must contain a 7-digit representation of the column number.

```
NOREF -NPINM A01X+009750Y+062880X0300Y1500
X+010000Y+063000 T01X+009750Y+063380 Z+000000
NOREF -NPINM A01X+009750Y+062880X0300Y1500
```

| 099NET1 | C0000000010 | T01X+009750Y+063380 Z+000000 |
|---|---|---|
| 327NET1 099NET1 | NOREF -NPINM | A01X+009750Y+062880X0300Y1500 B0000001P0000130 T01X+009750Y+063380 Z+000000 |
| 327NET1 | NOREF -NPINM | A01X+009750Y+062880X0300Y1500 |
| 099NET1 | P | T01X+009750Y+063380 Z+000000 |
| 327NET1 | NOREF -NPINM | A01X+009750Y+062880X0300Y1500 |

-----

```
099NET1
327NET1
099NET1
327NET1
099NET1
099NET1
```

| S0001 | T01X+009750Y+063380 Z+000000 |
|---|---|
| NOREF -NPINM | A01X+009750Y+062880X0300Y1500 |
| R0000005C0000015 | T01X+009750Y+063380 Z+000000 |
| NOREF -NPINM | A01X+009750Y+062880X0300Y1500 |
| X+010000Y+063000 | T01X+009750Y+063380 Z+000000 I0001 |
| X+055000Y+063000 | T01X+054750Y+062880 Z+000000 I0002 |

#### BLIND & BURIED VIAS

Blind and buried vias are described with a unique operation code. Blind vias use a continuation record to describe the surface feature that the via passes through.

| columns 1-3 | must contain code 307 |
|---|---|
| Columns 4-74 | Must be conform to the format of the Standard Electrical Test record, with the exception that the surface pad size must be omitted. The access side is inconsequential as the interpretation of the record will be taken from the start and end layers of the via |
| columns 75-77 | Contains "L" and a 2-digit representation of the physical layer in which the via connection starts. |
| columns 78-80 | Contains "L" and a 2-digit representation of the physical layer in which the via connection ends. |

Blind vias must have a 027 record attached, representing the surface feature associated with the via. Burried vias do not require attached records.

###### EXAMPLES

| 307NET1 | NOREF -NPIN D0150PA01X+009750+062880 | S0L01L03 |
|---|---|---|
| 027 | NOREF -NPIN | A01X+009550+062880X1200Y0500 |

```
307NET1 NOREF -NPIN D0150P X+009750+082880 S3L03L05
```

###### RESISTANCE AND CAPACITANCE MEASUREMENT INFORMATION

To allow the measurement of in-board and on-board resistance, capacitance, and inductance values. As two testpoints and two nets are involved in each measurement, each initial resistance, capacitance or inductance test location (operation code 370 or 380) must be immediately followed by a continuation record (operation code 070 or 080) that describes the second test point and net.

|  |  |
|---|---|
| First Record : |  |
| columns 1-3 | code 370 for inboard resistor, capacitor or inductor and 380 for on-board resistor, capacitor or inductor. |
| columns 4-17 | = Net Name / Node Number (no space allowed). If the net name is longer than 14 chars, then the parameter NNAME must be used in the P IMAGE PRIMARY section. |
| column 18 | must be left blank |
| columns 19-21 | column 19 must contain "A" for an access code, which is used to specify test point access side for the specific node which should be contacted during the measurement. This node location must match a record (317, 327 , 017 or 027) in the netlist portion of the file. Columns 20-21 must contain "00" if the point is accessible from both sides of the board or "01" or "NN" if the point is only accessible from one side of the board. |
| Column 22 | must be left blank |
| Column 23 | = Shall contain "X" to specify X test point location |

-----

| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
|---|---|
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 31 | = Shall contain "Y" to specify the Y test point location |
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |

| Column 39 | must be left blank |
|---|---|
| Column 40 | "R" to indicate a resistor , "C" for capacitor or "L" for Inductor |
| Column 41 | must be left blank |
| Columns 42-45 | A 4-digit representation of the actual value of the component. The units must be ohms for resistors , picoFarads for capacitance testing or picoHenries for inductors. |

Columns 46-48 The exponent value used to scale the value in columns 42-45 to the desired units. Column 46 must

contain an "E". Column 47 must contain a "+" or "-" for positive or negative exponent and column 48 must contain a single digit exponent. The values in columns 42-45 must be multiplied by 10 raised to the power of the exponent value presented in these columns.

| Column 49 | Must be left blank |
|---|---|
| columns 50-56 | these columns contain the low test value of the component. This is the lowest acceptable test value for this particular test. Columns 50-53 must contain a 4-digit representation of the component low test value. The units must be ohms for resistance testing and pico-Farads for |

capacitance testing. Column 54 must contain an "E". Column 55 must contain a "+" or "-" for positive or negative exponent and column 56 must contain a single digit exponent. The values in columns 50-53 must be multiplied by 10 raised to the power of the exponent value presented in these columns

| column 57 | Must be left blank |
|---|---|
| columns 58-64 | these columns contain the high test value of the component. This is the highest acceptable test value for this particular test. Columns 58-61 must contain a 4-digit representation of the component low test value. The units must be ohms for resistance testing and pico-Farads for |

capacitance testing. Column 62 must contain an "E". Column 63 must contain a "+" or "-" for positive or negative exponent and column 64 must contain a single digit exponent. The values in columns 58-61 must be multiplied by 10 raised to the power of the exponent value presented in these columns

| column 65 | Must be left blank |
|---|---|
| columns 66-79 | These are reserved for the component name. This field can be maximum 14 chars. |

###### Second Record :

| columns 1-3 | code 070 for inboard resistor, capacitor or inductor and 080 for on-board resistor, capacitor or inductor. |
|---|---|
| columns 4-39 | These are the same as in the First Record (see above) |
| Column 40 | = Shall contain "X" to specify X Resistor centroid location |
| Column 41 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 42-47 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 48 | = Shall contain "Y" to specify the Y Resistor centroid location |
| Column 49 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 50-55 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |

-----

| Column 56 | Must be left blank |
|---|---|
| Columns 57-66 | These columns are reserved for the X and Y dimensions of the component . Column 57 must contain "X" to indicate x-dimension and columns 58-61 must contain four digits to represent the dimension of the feature in 0.001 mm or 0.0001 Inch. Decimal points or leading zeroes omission is not allowed. Column 62 contains "Y" to indicate the y-dimension and 63-66 must contain four digits to represent the dimension of the feature in 0.001 mm or 0.0001 Inch. |
| column 67 | Must be left blank |
| Columns 68-70 | Must contain an "L" to indicate the layer on which this feature is located. Columns 69-70 must contain a 2-digit representation of the layer number on which the resistor is located. |
| EXAMPLE 370NET1 070NET1 Non-Test This record type will not defined other than test feature types has considered properly in record is not to be point record will have | A12 X+123456Y+123456 R 1234E+1 1234E+1 1234E+1 RESISTOR_NAME A12 X+123456Y+123456 X+123456Y+123456 X1234Y1234 L01 Features allow the placement of non-test features within the electrical test data. The shape of these features is a bounding rectangle to indicate the physical space occupied by the feature. An initial list of non- been created to provide a known vocabulary of these non-test features to assure they are the electrical test data. These features may duplicate existing test points, but the non-test feature considered a testable feature. If the non-test feature is at a location that requires testing, a second test to be created. |
| Columns 1-3 | Must contain operation code 390 |
| Columns 4-17 | Must contain a feature type name. There is a reserve list of feature type names as follows : MARK_PASS MARK_FAIL SUPPORT_POST TOOL_EDGE TOOL_FIXTURE TOOL_TESTER SHORTING_BLOCK TRANSFER_POINT ALIGN_POINT IMPEDANCE_TEST IMPED_TEST_REF HYPOT_TEST HYPOT_REF If the feature type is not in the above list, a name may be created. The above list should be considered dynamic as new feature type names will be added as needed after a common definition is agreed upon. The feature names must start in column 4. If the feature name does not occupy all of the columns from 4 to 17, the unoccupied columns must be left blank. |
| Column 18 | Must be left blank |
| Columns 19-21 | Column 19 must contain an "L" to indicate the layer on which this non-test feature is located. Columns 20-21 must contain a 2-digit representation of the layer number. If the layer number is "00", this non-test feature is assumed to reside on all layers. |
| Column 22 | Must be left blank |
| Column 23 | = Shall contain "X" to specify X non-test feature location |
| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |

-----

| Column 31 | = Shall contain "Y" to specify the Y non-test feature location |
|---|---|
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 39 | Must be left blank |
| Columns 40 | must contain "X" to specify the Non-test feature X-size |
| Columns 41-44 | must contain a 4-digit representation of the feature size in X-axis |
| Columns 45 | must contain "X" to specify the Non-test feature Y-size |
| Columns 46-49 | must contain a 4-digit representation of the feature size in Y-axis. If the feature is to be considered round, the Y value should be set to "0000", or the columns 45-49 can be left blank. |

#### Outlines

This record type will permit the description of outlines, and other segment type data that is not connected to a specific net. This record type follows the format of conductor data, with the following exceptions :

| Columns 1-3 | Must contain the operation code 389 to indicate an outline record. The code 089 indicates the continuation of an outline record |
|---|---|
| Columns 4-17 | Contain the outline type name. The allowable outline type names are as follows: BOARD_EDGE : represents the outline of a single image. This outline shall be stepped if included within the primary image data |

PANEL\_EDGE : represents the outline of the panel. Only one PANEL\_EDGE allowed in the file SCORE\_LINE : represents a scoring line. If this data is associated with the primary image, it shall be stepped accordingly. If SCORE\_LINE records are included in the P IMAGE PANEL section, the records shall not be stepped. OTHER\_FAB : represents other types of outlines that are not covered in the above list. This information shall also be stepped if associated with the primary image . If OTHER\_FAB records are included in the P IMAGE PANEL section, they will not be stepped. Columns 19-21 Must be left blank

- A drawing size shall be included for visual display purposes only. The actual center line of the draw shall

represent the edge.

###### EXAMPLE

```
389BOARD_EDGE X1234Y1234 X+123456Y+123456 X+123456Y+123456
089 X+123456Y+123456 X+123456Y+123456 X+123456Y+123456 X+123456Y+123456
```

#### High Voltage Test Information

High Voltage testing (hypot, breakdown, insulation resistance, etc.) is sometimes required on selected circuits or entire pcbs. High voltage test information can be included in the IPC-D-356A test file using the non-test feature operation code 390 and the continuation record 090, along with additional information in the records. The applied high voltage can be described using operation code 390. The reference point for the high voltage test can be described using operation code

090. Zero suppression is not allowed.

| columns 1-3 | must contain operation code 390 |
|---|---|
| columns 4-13 | must contain "HYPOT_TEST" |

-----

| columns 14-18 | must be left blank |
|---|---|
| column 19-21 | column 19 must contain "L" to indicate the layer on which this feature is located. Columns 20-21 must contain a 2-digit representation of the layer number. If the layer number is "00", this feature is assumed to reside on all layers |
| Column 22 | Must be left blank |
| Column 23 | = Shall contain "X" to specify X high voltage feature location |
| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 31 | = Shall contain "Y" to specify the Y high voltage feature location |
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 39 | Must be left blank |
| Columns 40 | must contain "X" to specify the high voltage feature X-size |
| Columns 41-44 | must contain a 4-digit representation of the feature size in X-axis |
| Columns 45 | must contain "X" to specify the high voltage feature Y-size |
| Columns 46-49 | must contain a 4-digit representation of the feature size in Y-axis. If the feature is to be considered round, the Y value should be set to "0000", or the columns 45-49 can be left blank. |
| column 50 | must be left blank |
| columns 51-64 | must contain the unique alphanumeric identifier for each high-voltage circuit network. |
| columns 65-68 | must contain the value of the applied high voltage, presented as a value and an exponent. Columns 65- 66 must contain a 2-digit representation of the applied test voltage. The units must be volts (either DC or RMS depending on whether the applied voltage is DC or AC, respectively). Columns 67-68 must contain the exponent value to scale the value in columns 65-66. Column 67 must contain "E". Column 68 must contain a single digit exponent. The value in columns 65-66 will be multiplied by 10 raised to the positive power of the exponent value in column 68. |
| column 69 | must contain "D" if the applied voltage is DC or "A" if the applied voltage is AC |
| column 70 | must be left blank |
| columns 71-75 | must contain the duration of the applied high voltage, presented as a value and an exponent. Columns 71-72 must contain a 2-digit representation of the duration of the applied test voltage. The units must be in seconds. Columns 73-75 must contain the exponent value to scale the value in columns 71-72. Column 73 must contain "E". Column 74 must contain a single digit exponent. The value in columns 71- 72 will be multiplied by 10 raised to the positive power of the exponent value in column 75. |
| column 76 | must be left blank |
| column 77-80 | must contain the maximal allowed leakage current, presented as a value and an exponent. Columns 77- 78 must contain a 2-digit representation of the maximal allowable leakage current. The units must be milliamps. Columns 79-80 must contain the exponent value to scale the value in columns 77-78. Column 79 must contain "E". Column 80 must contain a single digit exponent. The value in columns 77-78 will be multiplied by 10 raised to the positive power of the exponent value in column 80. |
|  |  |
|  |  |
|  |  |
|  |  |
|  |  |

The test point to which the applied high voltage should be referenced must be described in a continuation record. Note that this high voltage reference does not stand alone, but must be immediately follow a high voltage signal record.

| columns 1-3 | must contain operation code 090 |
|---|---|
| columns 4-13 | must contain "HYPOT_REF" |
| columns 14-18 | must be left blank |
| column 19-21 | column 19 must contain "L" to indicate the layer on which this feature is located. Columns 20-21 must contain a 2-digit representation of the layer number. If the layer number is "00", this feature is assumed |

-----

|  | to reside on all layers |
|---|---|
| Column 22 | Must be left blank |
| Column 23 | = Shall contain "X" to specify X high voltage reference feature location |
| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 31 | = Shall contain "Y" to specify the Y high voltage reference feature location |
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 39 | Must be left blank |
| Columns 40 | must contain "X" to specify the high voltage reference feature X-size |
| Columns 41-44 | must contain a 4-digit representation of the feature size in X-axis |
| Columns 45 | must contain "X" to specify the high voltage reference feature Y-size |
| Columns 46-49 | must contain a 4-digit representation of the feature size in Y-axis. If the feature is to be considered round, the Y value should be set to "0000", or the columns 45-49 can be left blank. |
| column 50 | must be left blank |
| columns 51-64 | must contain the unique alphanumeric identifier for each high-voltage circuit network. |
| Controlled Testing for controlled entails testing test file, using the in the records. The test point for the | impedance Test Information impedance (CI), on either single-ended or differential circuits, is sometimes required and usually testing either selected circuits or coupons on the pcbs. CI test information can be included in the IPC356A non-test feature operation code 390 and the continuation record 090, along with additional information test point which the CI should be measured can be described using operation code 390, the reference CI can be described using the operatio code 090. Zero suppression is not allowed. |
| columns 1-3 | must contain operation code 390 |
| columns 4-17 | must contain "IMPEDANCE_TEST" |
| column 18 | must be left blank |
| column 19-21 | column 19 must contain "L" to indicate the layer on which this feature is located. Columns 20-21 must contain a 2-digit representation of the layer number. If the layer number is "00", this feature is assumed to reside on all layers |
| Column 22 | Must be left blank |
| Column 23 | = Shall contain "X" to specify X impedance feature location. |
| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 31 | = Shall contain "Y" to specify the Y impedance feature location |
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 39 | Must be left blank |
| Columns 40 | must contain "X" to specify the impedance feature X-size |
| Columns 41-44 | must contain a 4-digit representation of the feature size in X-axis |

-----

| Columns 45 | must contain "X" to specify the impedance feature Y-size |
|---|---|
| Columns 46-49 | must contain a 4-digit representation of the feature size in Y-axis. If the feature is to be considered round, the Y value should be set to "0000", or the columns 45-49 can be left blank. |
| column 50 | must be left blank |
| columns 51-64 | must contain the unique alphanumeric identifier for each Controlled impedance circuit network. |
| column 65 | must be left blank |
| columns 66-68 | must contain the expected test value of the controlled impedance, presented as a value and an exponent. Columns 66-68 must contain a 3-digit representation of the expected CI test value. The units must be in ohms |

| column 69 | must be left blank |
|---|---|
| columns 70-72 | must contain the lowest expected test value of the controlled impedance, presented as a value and an exponent. Columns 70-72 must contain a 3-digit representation of the lowest expected CI test value. The units must be in ohms |
| column 73 | must be left blank |
| columns 74-76 | must contain the highest expected test value of the controlled impedance, presented as a value and an exponent. Columns 74-76 must contain a 3-digit representation of the highest expected CI test value. The units must be in ohms |

| column 77 | must be left blank |
|---|---|
| column 78 | must contain "S" if the CI test is single-ended, or "D" if the CI is differential. |

The test point to which the CI should be referenced must be described in a continuation record. Note that this CI reference does not stand alone, but must be immediately follow a CI test record.

| columns 1-3 | must contain operation code 390 |
|---|---|
| columns 4-17 | must contain "IMPED_TEST_REF" |
| column 18 | must be left blank |
| column 19-21 | column 19 must contain "L" to indicate the layer on which this feature is located. Columns 20-21 must contain a 2-digit representation of the layer number. If the layer number is "00", this feature is assumed to reside on all layers |
| Column 22 | Must be left blank |
| Column 23 | = Shall contain "X" to specify X impedance reference feature location. |
| Column 24 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 25-30 | = Shall contain six digits representing the X coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |
| Column 31 | = Shall contain "Y" to specify the Y impedance reference feature location |
| Column 32 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 33-38 | = Shall contain six digits representing the Y coordinate in 0.0001 Inch or 0.001mm. Leading zeroes may be blank. |

| Column 39 | Must be left blank |
|---|---|
| Columns 40 | must contain "X" to specify the impedance reference feature X-size |
| Columns 41-44 | must contain a 4-digit representation of the feature size in X-axis |
| Columns 45 | must contain "X" to specify the impedance reference feature Y-size |
| Columns 46-49 | must contain a 4-digit representation of the feature size in Y-axis. If the feature is to be considered round, the Y value should be set to "0000", or the columns 45-49 can be left blank. |
| column 50 | must be left blank |
| columns 51-64 | must contain the unique alphanumeric identifier for each Controlled impedance circuit network. |

-----



-----

#### Stepped images using the IMAGE parameter

To reduce the size of the IPC-D-356A file, an image may be stepped and repeated. This specification provides for the step and repeat of a single (primary) image only. To ensure that the data is properly interpreted, rules governing the step and repeat process have to be applied.

##### Defining the Primary Image

Only the "Primary" image may be stepped. The "Primary" image is indicated through the use of the "IMAGE PRIMARY" parameter. This parameter indicates the start of the "Primary" image data. This is the data that will be stepped. This data shall contain the netlist information, conductor data, adjacency information, and any other information associated with the netlist testing of a single image.

##### Defining a Stepped Image

A stepped image is indicated through the use of the "IMAGE" parameter followed by an image number. Following the indication of the stepped image, a Stepped Image Translation Record is provided to allow proper interpretation of the location of the stepped image.

Data included in the "IMAGE PANEL" section is not stepped. The Stepped Image Translation Record describes the steps required to properly position the stepped image using the primary image as the starting point

| columns 1-3 | must contain the operation code 309 to indicate a Stepped Image Translation record |
|---|---|
| column 4 | must be left blank |
| columns 5-6 | Column 5 must contain "M" and column 6 must contain "Y" if the primary image needs to be mirrored. |
| column 7 | must be left blank |
| columns 8-11 | column 8 must contain "R". Columns 9-11 must contain a 3-digit representation of the number of degrees of clockwise rotation applied to the image data. The only allowed values for the rotation are 0, 90, 180 and 270. Other values will be rounded to the closest allowable value. |
| column 12 | must be left blank |
| Column 13 | = Shall contain "X" to specify X offset |
| Column 14 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 15-20 | = Shall contain six digits representing the X offset |
| Column 21 | = Shall contain "Y" to specify the Y offset |
| Column 22 | = "+" or "-" or blank to specify the sign (blank = "+") |
| Columns 23-28 | = Shall contain six digits representing the Y |

##### Allowed Operations

In the step & repeat process, a number of operations can be performed. The allowed sequence of operations are as follows : MIRROR

ROTATE OFFSET There may be only one of each operation per stepped image, and they must be performed in the above listed order.

##### Mirror

When an image is mirrored, it is to be mirrored around the Y axis. The resultant translation of the coordinates is : Mirrored X = -(X) ; Mirrored Y = Y Mirroring also results in the reversal of layer numbers for that image. In a 4-layer board this would result in the following :

-----

Mirrored Layer 1 = Layer 4 Mirrored Layer 2 = Layer 3 Mirrored Layer 3 = Layer 2 Mirrored Layer 4 = Layer 1

##### Rotation

Rotation is to be preformed in clockwise direction around (0,0). The translations are as follows :

|  | X | Y |
|---|---|---|
| 0 | X | Y |
| 90 | Y | -X |
| 180 | -X | -Y |
| 270 | -Y | X |

##### Offset

The offset value must be added to the corresponding coordinate as follows: Offset X = X + X Offset Offset Y = Y + Y Offset

-----

### GRAPHICAL EXAMPLES

##### EXAMPLE 1

| .038" Round Pad | Access A01 |
|---|---|
| .020" Drilled Hole Access | A00 |
| .038" Round pad | Access A04 |

---

This most simple case can be represented in several ways. For backward compatibility a single 317 record would do, but alternately three records could be included. Each feature has the same x/y location. Round pad sizes defined by X-value, Y value set to zero or left blank

###### Complex record representation

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPIN | A01X+009750Y+062880X0380Y | S0 |
|---|---|---|---|
| 017NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X0380Y D0200PA00X+009750Y+062880 | S0 |

###### Single record representation

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
317NET1 NOREF -NPIN D0200PA01X+009750Y+062880X0380Y S0
```

###### EXAMPLE 2

| .038" Round Pad | Access A01 |
|---|---|
| .020" Drilled Hole Access | A00 |
| .038" Square pad | Access A04 |

---

In this example, the square pad on side 4 could be represented by a 027 or 017 record. The shape of the feature is garnered from columns 58-67 which describe a square pad.

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPIN | A01X+009750Y+062880X0380Y | S0 |
|---|---|---|---|
| 017NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X0380Y0380 D0200PA00X+009750Y+062880 | S0 |

-----

##### EXAMPLE 3

---

.120" x .020" SMD Pad Access A01 .015" Drilled Hole Access A00 .120" x .020" SMD Pad Access A04

---

327 record for SMD (top) , 027 record for SMD (bottom), 017 record for drill. Each record with same x/y. Ovals, rounded rectangles or chamfered rectangles defined as rectangular pads.

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 327NET1 | NOREF -NPIN | A01X+009750Y+062880X1200Y0200 | S0 |
|---|---|---|---|
| 027NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X1200Y0200 D0150PA00X+009450Y+062880 | S0 |

##### EXAMPLE 4

---

.038 Round Pad Access A01 .015" Drilled Hole Access A00 .120" x .020" SMD Pad Access A04

---

317 record for top point , 017 record for drill hole , 027 for bottom smd.

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPIN | A01X+009750Y+062880X0380Y | S0 |
|---|---|---|---|
| 027NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X1200Y0200 D0150PA00X+009450Y+062880 | S0 |

##### EXAMPLE 5

No top mask clearance

| .050" Round Pad | Access A01 |
|---|---|
| .020" Drilled Hole Access | A00 |
| .038" Round pad | Access A04 |

---

317 record for top point , 017 record for drill hole , 017 record for bottom point.

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPIN | A01X+009750Y+062880X0500Y | S1 |
|---|---|---|---|
| 017NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X0380Y D0150PA00X+009450Y+062880 | S1 |

-----

##### EXAMPLE 6

---

No top mask clearance .050 Round Pad Access A01 .015" Drilled Hole Access A00 .120" x .020" SMD Pad ~~ Access A04

---

317 record for top point , 017 record for drill hole , Inner layer connection to other points on the net, 017 or 027 record for bottom point.

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPIN | A01X+009750Y+062880X0500Y | S1 |
|---|---|---|---|
| 017NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X1200Y0200 D0150PA00X+009750Y+062880 | S1 |

| EXAMPLE 7 317 record for each top point,midpoint flag on. 017 for each drill hole; 017 for each bottom point |
|---|
| 00000000011111111112222222222333333333344444444445555555555666666666677777777778 12345678901234567890123456789012345678901234567890123456789012345678901234567890 |
| 317NET1 017NET1 017NET1 317NET1 017NET1 017NET1 |

No top mask clearance

| .038" Round Pad | Access A01 |
|---|---|
| .020" Drilled Hole Access | A00 |
| .038" Round pad | Access A04 |

```
NOREF -NPINM
NOREF -NPIN
-
NOREF -NPINM
NOREF -NPIN
-
```

```
A01X+009750Y+062880X0380Y
A04X+009750Y+062880X0380Y
D0200PA00X+009750Y+062880
A01X+019750Y+062880X0380Y
A04X+019750Y+062880X0380Y
D0200PA00X+019750Y+062880
```

```
S1
S1
S1
S1
```

-----

##### EXAMPLE 8

| ~~ NM |  | No top mask clearance on round pad .062" Round Pad ; .120" x .020" SMD Pad ~~ Access A01 |
|---|---|---|
| AN CY | .038" Drilled Hole & .015 Drilled hole | Access A00 |
|  | .062" Round pad & .038 round pad A04 | Access |

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 317NET1 | NOREF -NPINM | A01X+009750Y+062880X0620Y | S1 |
|---|---|---|---|
| 017NET1 017NET1 | NOREF -NPIN - | A04X+009750Y+062880X0620Y D0380PA00X+009750Y+062880 | S1 |
| 327NET1 | NOREF -NPIN | A01X+200000Y+062880X0120Y0020 | S1 |
| 017NET1 017NET1 | NOREF -NPIN - | A04X+019750Y+062880X0380Y D0150PA00X+019750Y+062880 | S1 |

###### EXAMPLE 9

---

.120" x .025" SMD Pad Access A01 .015" blind via from L1 to L2 Access A00 .120" x .025" SMD Pad Access A04

---

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
```

| 307NET1 |  | NOREF -NPIN D0150PA01X+009750Y+062880 |
|---|---|---|
| 027NET1 | NOREF -NPIN | PA04X+009550Y+062880X1200Y0250 |
| 327NET325 | NOREF -NPIN | A04X+009750Y+062880X1200Y0250 |

```
S0L01L02
S0
S0
```

-----

##### EXAMPLE 10 : 6 layer board with inner resistor

Net: Sig24 Ref. Des.: U28-14

Net: Sig23 Ref. Des.: U27-14 Internal resistor name R153,

Nominal size 0.050"x0.050" Value: 100 ohms, +/- 10%

```
00000000011111111112222222222333333333344444444445555555555666666666677777777778
12345678901234567890123456789012345678901234567890123456789012345678901234567890
IMAGE PRIMARY
```

| 317SIG23 | VIA | MD0150PA00X+006400Y+062000X0620Y | S3 |
|---|---|---|---|
| 327SIG23 | U28 | PA01X+005400Y+062000X0800Y0150 | S0 |
| 314SIG24 | VIA | MD0150PA00X+008900Y+062000X0620Y | S3 |
| 327SIG23 378SIG23 | U27 | PA06X+005400Y+062000X0800Y0150 L01 X50 X6400Y6200 X5400 | S0 |
| 378SIG23 378SIG23 |  | L04 X50 X6400Y6200 X7400*X7400Y5950 Y6450 L01 X50 X8900Y6200 X9900 |  |
| 378SIG23 370SIG23 |  | L04 X50 X7900Y6200 X8900*X7900Y5950 Y6450 A01 X+005400Y+062000 R 0100E+1 0090E+1 0110E+1 R153 |  |
| 070SIG24 |  | A06 X+009900Y+062000 X+007650Y+06200 X0500Y00500 |  |

Note that if the ends of the resistor were formed with flashed pads rather than drawn segments , they could be represented with 327 records with access A04 to indicate internal flashed pad location. The location and sizes of resistors , capacitors , and inductors are optional fields. Some designs preclude their use, but they are recommended for applicable designs to allow visual representation of the resistors in graphical based repair systems.