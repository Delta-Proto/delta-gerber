![](image_p1_0.png)

# Q DownStream

n o

## IPC-D-356 Simplified

### Written by Rich Nedbal

### Scope:

Everybody has heard of the IPC by now, and a few of you out there have actually tried to use some of the IPC formats. What you may not know is that the IPC-D-356 format is actually being used successfully every day by hundreds of people for bare board test. However, as is the case for most format specifications, the documentation is hard to read, and about as exciting to read as the dictionary (actually the dictionary has it beat on several points). The purpose of this paper is to try to demystify the format, and to make it clear what it is for and how it works.

### Purpose:

The IPC-D-356 format was designed to define a standard netlist format by which bare board test information can be represented. In the simplest of forms, this means it must be able to store netlist information along with XY coordinates, and even reference designators and pin numbers (if they exist). I feel like ending this article here before it gets unmanageable, but then Pete Waddell wouldn't be happy, so let's proceed. If all the information that a test system would need to know to verify and test a bare board could be represented in a single file, it would bring consistency to the testing world. IPC-D- 356 does just that.

### Does it work?

Yes. You will find that virtually all the test equipment manufacturers either support the IPC-D-356 format now, or are in the process of supporting it. Are there problems with it? Of course. Certain special pad types are not supported and additions could be made which would assist computer aided repair stations, but all in all it's pretty good.

### Why did the IPC-D-356 format get accepted when other "standards" didn't?

This is a very good question. I speculate that it caught on because it was simple to understand, ASCII based, neutral (read non-competitive), and fairly comprehensive. It basically is a format that tells you where the nets are so they can be probed and tested. As an example of how its comprehensiveness works to your advantage, let's look at the netlist data itself. I need a netlist to program my bare board tester. I have a netlist from my CAD system but, lo and behold, each net is described using reference designators and pin numbers. The RefDes/Pin# netlist syntax makes sense for an intelligent CAD database, but bare board testers get Gerber data. OK, so let me have the netlist using a syntax that uses coordinates. Coordinates make my tester happy but what if I wanted to see the results using the RefDes/Pin#? The IPC-D-356 format is very friendly because you can have net coordinates, or RefDes/Pin#, or both.

-----

Here is an example of a CAD netlist that uses reference designators and pin numbers:

```
% COMPONENTS
SN7442 U1
CD4008 U2
```

### … more components …

```
% SIGNALS
Clk65 U2-5,U3-3,U7-4,U11-9
Sig22 U2-10,U6-10,U11-5
Clock73 U2-9,U3-4,U6-2,U7-9,U10-1
Data82 U2-4,U5-10,U7-10,U10-8,U11-1
Sig26 U2-3,U3-2,U5-3
```

### … more nets …

Here is an example of the same netlist using coordinates:

```
% COORDINATES
Clk65 (2300.0:1200.0), (2100.0:1700.0), (2200.0:3900.0),
(3400.0:3000.0)
Sig22 (2300.0:1500.0),(2300.0:3500.0),(3300.0:2700.0)
Clock73 (2400.0:1500.0), (2200.0:1700.0), (2000.0:3200.0),
(2400.0:4200.0), (2900.0:2200.0)
Data82 (2200.0:1200.0), (2300.0:3000.0), (2300.0:4200.0),
(3500.0:2500.0), (2900.0:2700.0)
Sig26 (1850.0:2600.0), (2100.0:1200.0), (2000.0:1700.0)
```

### How is IPC-D-356 used:

The IPC-D-356 format can carry a lot of information such as comments, format specifiers, field parameters, etc. But the intent of this article is not to try to make you an IPC-D-356 expert; but rather cover the basics. Perhaps the most basic point is where do you get the file and what do you do with it. Since testing bare boards is a manufacturing process, the design engineer seldom thinks about the problem of testing the PCB before it is stuffed with parts. Therefore, most CAD packages do not even provide the option of exporting a coordinate based netlist in this format. CAM software is the common source of this information. Net connectivity is computed from the Gerber data and the file is exported. Nets are numbered because the original CAD netlist was not used. The RefDes/Pin# fields are also not filled in because this information does not exist in the Gerber data. The IPC-D-356 file is then read by the bare board test system. The net information replaces the need for the old "golden board" technique. This process is the way the majority of the industry is working today. It is becoming more popular for CAM software to be able to read the original CAD netlist as well, thereby verifying that the computed netlist connectivity is correct and changing the net numbers into their original net names. Again, IPC-D-356 can be used in either case.

-----

### The Format:

Fixed Line Length The typical IPC-D-356 file format is the well-known 80 column ASCII record. This means every line is made up of 80 columns and the field locations become of paramount importance. What I mean is that data located in column 23, with the next data located at column 30, better have spaces that fill in columns 24 - 29. There are ways to describe lines of varying length, but few systems support this feature. Data inside a field is also left justified, meaning that spaces must be used to fill out unused field locations. Record Types There are may different types of records, but the most common are Comments, Parameters, Test Records and End of File. So … to make it real easy …

```
Column number: 1 2 3
```

| C _ _ | Comment record |
|---|---|
| P _ _ | Parameter record |
| 3 # # | Test record |
| 9 9 9 | End of file |

Notice that columns 2 and 3 are blank for both the Comment and the Parameter records. The 3 signifies that this record is a test record.

-----

### Record Field Summary:

When I was first looking into the IPC-D-356 format, I found it difficult to get an overview of the record structure without looking at examples. After reviewing several files I made a summary chart which helped me identify data more quickly than trying to refer to the documentation all the time.

| Column | Data | Description |
|---|---|---|
| Number | Meaning |  |
| 1 | P,C,3 | C=comment, P=parameter, 3=test record |
| 2 | 1,2,5,6 | 1=through hole, 2=SMT feature, 3=tooling |

```
feature/hole, 4=tooling hole only
```

| 3 | 7 | Always a 7 for a test record 999 = end of file |
|---|---|---|
| 4-17 | Net Name | Alphanumeric string (yes, nets name are limited to 14 characters) |
| 18-20 | --- | These fields are left blank for finished PCBs. |
| 21-32 | Ref Des | This is where the reference designator goes if it is known. |
| 21-26 | ID,VIA | RefDes (U or IC) or "VIA" if it is a via |
| 27 | - | Always a dash. ie U-17 or IC-12 |
| 28-31 | Alpha# | Component pin number |
| 32 | M | M means a point in the middle of a net. Blank means the end of a net. |
| 33-38 | Hole type | Hole definition field |
| 33-37 | D##### | D=diameter, #=size in .0001 inches or .001 mm. |
| 38 | P or U | P=plated, U=unplated |
| 39-41 | A## | A=access side of PCB. available from both sides. 01 if Primary side |

```
only. >01 means internal layers.
```

| 42-57 | Coords | X, Y coordinates of test location. |
|---|---|---|
| 42 43 | X | Start of X coordinate +- or blank X coordinate polarity |
| 44-49 | value | 6 digits to .0001 inches. (No decimal points.) Leading zeros surpressed. |
| 50 51 | Y | Start of Y coordinate +- or blank Y coordinate polarity |
| 52-57 | value | 6 digits to .0001 inches. (No decimal points.) Leading zeros surpressed. |
| 58-71 | Rect Data | Dimensions for rectangular test feature. |
| 58-62 | X#### | X dimension of feature in .0001 inches |
| 63-67 | Y#### | Y dimension of feature in .0001 inches (Fields 68 - 80 are often left blank.) |
| 68-71 72 | R### | Rotation of feature in whole degrees. Not used. Must be left blank. |
| 73-74 75-80 | S# | Optional solder mask information. Optional test record. Commonly left blank. |

-----

Here is a sample IPC-D-356 file that goes with the previous netlist examples:

```
C CAM350 V4.02 Date: Thu Nov 21 16:48:27 1996
C Database: c:\act40\ipcdemo.pcb
C
```

| P | JOB | CAM350 V4.02 NETLIST, DATE: Thu Nov 21 16:48:27 1996 |  |  |  |  |  |
|---|---|---|---|---|---|---|---|
| P | UNITS CUST 0 |  |  |  |  |  |  |
| P | DIM | N |  |  |  |  |  |
| 317Clk65 |  |  | U2 | -5 | D 400PA00X | 23000Y | 12000X 600Y 600 |
| 317Clk65 |  |  | U3 | -3 | D 400PA00X | 21000Y | 17000X 600Y 600 |
| 317Clk65 |  |  | U7 | -4 | D 400PA00X | 22000Y | 39000X 600Y 600 |
| 317Clk65 |  |  | U11 | -9 | D 400PA00X | 34000Y | 30000X 600Y 600 |
| 317Sig22 |  |  | U2 | -10 | D 400PA00X | 23000Y | 15000X 600Y 600 |
| 317Sig22 |  |  | U6 | -10 | D 400PA00X | 23000Y | 35000X 600Y 600 |
| 317Sig22 |  |  | U11 | -5 | D 400PA00X | 33000Y | 27000X 600Y 600 |
| 317Clock73 |  |  | U2 | -9 | D 400PA00X | 24000Y | 15000X 600Y 600 |
| 317Clock73 |  |  | U3 | -4 | D 400PA00X | 22000Y | 17000X 600Y 600 |
| 317Clock73 |  |  | U6 | -2 | D 400PA00X | 20000Y | 32000X 600Y 600 |
| 317Clock73 |  |  | U7 | -9 | D 400PA00X | 24000Y | 42000X 600Y 600 |
| 317Clock73 |  |  | U10 | -1 | D 400PA00X | 29000Y | 22000X 600Y 600 |
| 317Data82 |  |  | U2 | -4 | D 400PA00X | 22000Y | 12000X 600Y 600 |
| 317Data82 |  |  | U5 | -10 | D 400PA00X | 23000Y | 30000X 600Y 600 |
| 317Data82 |  |  | U7 | -10 | D 400PA00X | 23000Y | 42000X 600Y 600 |
| 317Data82 |  |  | U10 | -8 | D 400PA00X | 35000Y | 25000X 600Y 600 |
| 317Data82 |  |  | U11 | -1 | D 400PA00X | 29000Y | 27000X 600Y 600 |
| 317Sig26 |  |  | VIA |  | D 280PA00X | 18500Y | 26000X 400Y 400 |
| 317Sig26 |  |  | U2 | -3 | D 400PA00X | 21000Y | 12000X 600Y 600 |
| 317Sig26 |  |  | U3 | -2 | D 400PA00X | 20000Y | 17000X 600Y 600 |
| 317Sig26 |  |  | U5 | -3 | D 400PA00X | 21000Y | 27000X 600Y 600 |
| 999 |  |  |  |  |  |  |  |

Let's analyzed one test record in detail:

```
317Clk65 U2 -5 D 400PA00X 23000Y 12000X 600Y 600
```

| 317 | This is a test record for a through hole. |
|---|---|
| Clk65 | The net name is Clk65 |
| U2 -5 | This particular test point is U2 pin #5 |
| D 400 | The hole diameter is 40 mils (.0400 with the leading zero suppressed) |
| P | It is plated through |
| A00 | It's available from both sides of the board It's located at X 2.3 and Y 1.2 (again, leading zeros are suppressed) |

It's a 60 mill pad feature (.0600)

**Is That All There Is?**

From the above simple example it should be clear that the IPC-D-356 file format is fairly simple to understand. The 80 column character-based format is antiquated by today's standards, but it is well suited for this application. A free form, keyword driven, objectoriented syntax would be more modern and probably more extensible, but as it is, it does pretty well. You could even argue that its simplicity and limited scope are some of the reasons it has caught on.

So the question arises, "What's missing?" It is natural to expect that whenever netlist information enters the manufacturing world it should be used to its fullest for additional functions such as computer aided repair, electrical verification and even back annotation to the CAD system. We should not let our fervor get the best of us because if we tried to squeeze to much out of the format it would lose its best quality - simplicity. But I never let things lie even when I should.

-----

### Some Enhancements Would Be Nice:

First I would change from the 80 column format. It just doesn't make sense today. Once I had the ability to use key words then I could argue for a few enhancements to the data itself without increasing the complexity. Here are a few manufacturing operations that could be enhanced:

- Pad descriptions. The current format doesn't really tell you much about the type of

pad that is going to be probed. Special pads shapes are becoming common.

- Netlist Adjacency. "Flying Probe" test systems are an economic alternative to dropping test probes on every point. In reality, all the test system really needs to do is to probe those nets that could be a problem. Statistically speaking, testing a net to its logical partners is much more efficient than probing every point. The flying probe philosophy has already proven itself in the real world so there is no reason why the adjacency information could not be included in the format itself.
- Z axis information. For the most part PCBs are still 2 dimensional entities, but the

shrinking size requirements is causing more PCBs to end up as MCMs or Hybrids. These designs have multiple levels or depths that must be handled.

- SMT pads with vias. It is becoming more common for there to be a via directly under

an SMT pad. Is this still an SMT pad or is it a through hole? Pad descriptions together with which side to probe would make this much easier.

- Solder Masks. The centroid of a pad may be covered by a solder mask. Here again,

an adequate pad description with an offset or probe location would take care of this.

- Screened resistors, etc. Passive components are often screened onto the PCB. These

can cause continuity failures if not adequately defined. You could argue that screened components are not part of a "bare board," but they can be internal and therefore part of the finished bare board by default.

- Ends of nets or specific test points. There are cases where the designer has made

specific locations for testing that may not be part of the CAD netlist. However, today's CAM systems make these points fairly simple to find and easy to filter prior to the final exporting of the netlist.

### Summary:

The good news is that the IPC-D356 format IS a standard and does a pretty good job, given its simplicity and its objective of providing bare board coordinate-based netlist information with additional data if desired. It is capable of handling the reference designators and pin numbers also, but does not require that they exist. For bare board testing there's no simpler way to go.