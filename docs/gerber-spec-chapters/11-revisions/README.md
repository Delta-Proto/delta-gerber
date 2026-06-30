# 11 Revisions

## 11.1 Revision 2024.05

The mount type attribute .CMnt,(TH|SMD|Fiducial|Other) was replaced by

```
.CMnt,(TH|SMD|Pressfit|Other).
```

Added section 5.1.1 on comment attributes. Corrected text errors, pointed out by Krzysztof Wiśniewski. Corrected an error in the formal syntax in 4.2.2 and a text error in 8.1.10, pointed out by Bruce McKibben. The graphics state after an AB definition was clarified. Clarifications related to fiducials, based on suggestions by Jim J. Jewett. Corrected typos pointed out by Jim J. Jewett. Revision 2024.05 was developed by Karel Tavernier.

## 11.2 Revision 2023.08

The mandated aperture for pins other than pin 1 in the component layers was modified from zero-size to a circle of 0.1mm or 0.004 inch, after discussion with Wim De Greve, Jean-Pierre Charras and Dirk Leroy. The order of mirroring and rotation for aperture transformations in section 4.9.1 was clarified, as suggested by Adam Newington. Errors in examples pointed out by Hans Forssell were corrected. The `.Cxxx` standard attributes were added to the synoptic table in 5.1. Revision 2023.08 was developed by Karel Tavernier.

## 11.3 Revision 2023.03

The version field in the .GenerationSoftware file attribute was made mandatory, see 5.6.7. We corrected an error in the macros in section 4 .5.5.4 and in 6.5, pointed out by Tony Luken. We clarified that the %TD command has no effect on the file attributes, an ambiguity that was pointed out by Hardy Woodland; see sections 5.2 and 5.5. Revision 2023.03 was developed by Karel Tavernier.

## 11.4 Revision 2022.02

The formal grammar in section 3.5 was simplified, without changing the language itself. Section 4.2.2. Format Specification (FS) and section 4 .10.1 Region Overview were rewritten. Typos pointed out by Nicholas Meeker were corrected. The awkward term interpolating was replaced by plotting. Revision 2022.02 was developed by Karel Tavernier.

## 11.5 Revision 2021.11

Several improvements in the description of component attributes suggested by Bruce McKibben. Improved the text of the .CRot spec, as suggested by Urban Bruhin. Corrected a typo, pointed out by Bradley Lin. Corrected an error in the EBNF grammar of .Part, pointed out by Paul M. Johnson. Corrected an error in 4 .2.1 and 4.9.1 pointed out by Grzegorz Kimbar.

-----

Corrected a large number of typos and errors pointed out by Frog Chen - we expressly thank him for his very careful review of the spec. Revision 2021.11 was developed by Karel Tavernier.

## 11.6 Revision 2021.04

We fixed errors and typos pointed out by Bruce Mc.Kibben and Jim J. Jewett - we thank them for the very careful proof reading. The definition of the coordinate system was added to 4.2, as suggested by Graham Wideman, who helpfully supplied the illustrations. Revision 2021.04 was developed by Karel Tavernier.

## 11.7 Revision 2021.02 - Formal grammar

Deprecated single quadrant mode (G74) and macro primitive code 6 (moiré). The specification now includes a formal grammar for the complete format. See 3.5. Several typos were fixed thanks to Nick Meeker and Kevin Shi for their careful review. The term 'data block' was replaced by 'word' to eliminate confusion with aperture blocks. Revision 2021.02 was developed by Karel Tavernier.

## 11.8 Revision 2020.09 - X3

In March 2020 a specification for including component information - Gerber X3 2020.03 - was published. Gerber X3 was developed in discussion with a team of people. Karel Tavernier developed a prototype specification which was circulated privately with Wim De Greve, Jean- Pierre Charras, Thiadmer Riemersma, Bruce McKibben and Rafal Powierski in December 2018. In intense discussion among this group the draft went through five revisions until a first public draft was published in October 2019, calling for input from the user community. The review process was closed in February 2020 with the publication of the separate X3 specification, now merged in this document. This separate specification on components is now merged into this main specification. Gerber now accepts Unicode characters for attribute values that contain user-defined metainformation, and therefore may require special characters and other languages than English. Draws with rectangular apertures were deprecated, see 8.2.3, as were some style variations in word commands, see 8.3.3. Clarified the semantics of the .GenerationSoftware attribute. Specified more clearly how drill files must be constructed, as suggested by Wim De Greve and Luc Samyn. Fixed errors pointed out by Greg Huangqi and Jim J. Jewett. Especially Jim J. Jewett reviewed the specification in great detail. Replaced the unusual term 'modifier' with the more usual term 'parameter'. Revision 2020.09 was developed by Karel Tavernier.

## 11.9 Revision 2019.09

Replaced the option [Filled|NotFilled] on the ViaDrill value of the .AperFunction attribute with the more specific IPC-4761 types, see 5.6.10. Revision 2019.09 was developed by Karel Tavernier.

-----

## 11.10 Revision 2019.06

The .AperFunction values CutOut, Slot and Cavity were deprecated. See 8.3. Made it more explicit that macro aperture names cannot be reused. Corrected an error in the examples pointed out by Abe Tusk, and in 2 .1, pointed out by Radim Halíř. Revision 2019.06 was developed by Karel Tavernier.

## 11.11 Revision 2018.11

Removed the .PF attribute, and replaced its content by an additional optional field to the .P attribute. See 5.6.14. Fixed a number of typos and minor errors pointed out by Jörg Naujoks, Rik Breemeersh and Radim Halíř. Revision 2018.11 was developed by Karel Tavernier.

## 11.12 Revision 2018.09

Corrected an error in the polygon aperture, section 4.4.5, and polygon primitive, section 4.5.1.7. Clarified the rotation of the deprecated rectangular holes in apertures, section 8.2.2 These issues were pointed out by Remco Poelstra. Corrected an error in the moiré primitive specification, section 8.2.6. The error was pointed out by Vasily Turchenko. Clarified how object attributes are attached to regions, triggered by remarks from Radim Halíř. Defined allowed range of the scale factor in 4 .9.5, as suggested by Andreas Weidinger. Defined orientation of text mirroring in section 5 .6.12. Triggered by Nicholas Meeker. Nicholas Meeker, Andreas Weidinger, Radim Halíř and Denis Morin carefully proofread the document, which resulted in many text corrections. Revision 2018.09 was developed by Karel Tavernier.

## 11.13 Revision 2018.06

Removed PressFit option from the ComponentPad attribute value; it is also a ComponentDrill option and that is sufficient. Clarified pad attribute values for via, component, SMD, BGA on inner layers. Clarified FS command, see 4.1 and 8.2.1. Fixed broken links to references indicated by Vasily Turchenko. Revision 2018.06 was developed by Karel Tavernier.

## 11.14 Revision 2018.05

Added .PF attribute, as suggested by Matthew Sanders. Corrected errors in an example in 4.9.1 pointed out by Erik Forwerk. Corrected errors in the SR definition and Backus-Naur form pointed out by Remco Poelstra, see 4.11. Simplified the Backus-Naur form of the region statement, see 4.10.2. Corrected an error in 5.4 pointed out by Dries Soentjens. Revision 2018.05 was developed by Karel Tavernier.

-----

## 11.15 Revision 2017.11

Allow the .N attribute not only on copper layers but also on plated drill layers, see 5.6.13. Remove .FileFunction value Keep-out. Use Profile instead. Specified that to combine files zip is the only allowed archive format, as suggested by Rafal Powierski. Simplified the Backus-Naur form of aperture blocks, see 4.11.2. Added synoptic table with macro primitives in 4.5.1.1. Added synoptic table one with standard apertures in 4.4.1. Added Backus-Naur form of the region statement. Added link to the Reference Gerber Viewer in 1.3. Fixed typos pointed out by Forest Darling. Fixed a number or typos pointed out by Radim Halíř. Revision 2017.11 was developed by Karel Tavernier.

## 11.16 Revision 2017.05

Added the new file attribute .SameCoordinates, see 5.6.5. Added file functions Depthrout, Viafill, Vcut, and Vcutmap. Created section 5.6.16 with guidelines on the use of attributes in fabrication data; added guidelines on how to define the PCB profile in 6 .2. Reorganized and edited the chapter Overview. Clarified section on zero-size apertures. Corrected an error in the comment in example 6 .6.2 pointed out by Nav Mohammed. Corrected errors in the examples in 5.6.5 pointed out by Rik Breemeersch, Revision 2017.05 was developed by Karel Tavernier.

## 11.17 Revision 2017.03

Added section 5.6.16, specifying how to put text in the image. Changed file function Gluemask to Glue; added explanation; see 5.6.3. Reorganized chapter 3.5. Extended section 4 .10.5. Corrections in 4.9.1, 4.9.5, 4.10.1 and in Aperture Attributes on Regions triggered by remarks from Remco Poelstra. Corrected an error in an example pointed out by Danilo Bargen. Revision 2017.03 was developed by Karel Tavernier.

## 11.18 Revision 2016.12 - Nested step and repeat

This is a major revision with powerful new imaging functions: 4.11, 4.9.3, 4.9.4 and 4.9.5. These allow nested step and repeat to define panels efficiently, see 4.11.3 and 0. Thera are fixes for errors in examples, pointed out by Danilo Bargen and Urban Bruhin. Revision 2016.12, and especially the new imaging function for panels was developed by Karel Tavernier and Rik Breemeersch. The first draft of these functions was published in August 2016. During the public review process. Thomas Weyn, Bruce McKibben, Masao Miyashita and Remco Poelstra provided essential input.

-----

## 11.19 Revision 2016.11

This major revision allows to include the CAD netlist to Gerber files by adding three new standard object attributes - see 5.6.16 above. The goal of the Gerber CAD netlist is to facilitate upfront communication between the different parties involved in design, assembly and automation. The X2 attributes proposed include CAD netlists in Gerber fabrication data and allow to:

- Attach the component reference designator, pin number and net name to the component

pads in the outer copper layers. This information is essential for a complete board display and for a complete board display. More importantly, the netlist provides a powerful checksum to guarantee PCB fabrication data integrity.

- Attach the netlist name to any conducting object on any copper layer. Lightweight

viewers can then display netlists without the need for an algorithm to compute connectivity

- Attach the component reference to any object, e.g. to identify all the legend objects

belonging to a given component, for example. Several text improvements. Section 4.10.3 on regions clarified triggered by deep questions asked by Remco Poelstra. Revision 2016.11 was developed by Karel Tavernier. Jean-Pierre Charras provided essential input on the CAD netlist, and further remarks by Remco Poelstra and Wim De Greve were included.

## 11.20 Revision 2016.09

New or modified attribute values - see 5.6.10:

- Replaced file function Drawing with OtherDrawing.
- Added the optional field Filled|NotFilled to ViaDrill.
- Added aperture function EtchedComponent. Added object attributes - see 5.4. Object attributes attach information to individual graphical objects. Corrected an error in example 4.10.4.6. The error was pointed out by Thomas van Soest and Siegfried Hildebrand. Clarified the syntax of attaching aperture attributes to regions. Added Perl script to show precisely how to calculate the .MD5. Several other clarifications. Revision 2016.09 was developed by Karel Tavernier.

## 11.21 Revision 2016.06

Added a section on back-drilling job triggered by questions from Alexey Sabunin. See 6.6.1. The .ProjectID UUID was changed to RFC4122; rewritten by Remco Poelstra. See 5.6.8. Aperture function attributes were clarified triggered by remarks from John Cheesman. Drill sizes were clarified triggered by remarks from Jeff Loyer.

## 11.22 Revision 2016.04

Added PressFit label to component drill and pad attributes; see ComponentPad and ComponentDrill. Revoked default on current point. Text improvements that do not change the format: Removed superfluous concept of level and replaced the name 'Level Polarity' by 'Load Polarity. Various other text improvements.

-----

## 11.23 Revision 2016.01

Added drill and pad functions for castellated holes. Added optional types break-out and tooling on MechanicalDrill. Deprecated closing an SR with the M02. Text improvements that do not change the format: Clarified .AperFunction attribute values. Clarified when to use of standard or user attributes. Clarified how aperture attributes can be set on regions.

## 11.24 Revision 2015.10

Added items to section Errors and Bad Practices. Added file function attribute .FilePolarity. Refined drawing .FileFunction attributes Replaced Mechanical by FabricationDrawing and Assembly by AssemblyDrawing. Added definitions to the drawing types. Added mandatory (Top|Bot) to .AssemblyDrawing, as suggested by Malcolm Lear. Added ArrayDrawing.

## 11.25 Revision 2015.07

The superfluous and rarely, if ever, used macro primitives 2 and 22 were revoked. The .AperFunction aperture attribute was simplified:

- Filled / NotFilled option is removed for the ViaDrill function
- ImpC / NotC option is removed from the Conductor function

## 11.26 Revision 2015.06

New file attributes were specified: .GenerationSoftware (5.6.5), .CreationDate (5.6.5) and *.ProjectId (5.6.8).* The mistakenly omitted rotation parameter of the circle macro primitive was restored. Unicode escape sequences in strings are now defined. Operation syntax combining the G and D codes in a single word were deprecated. The rectangular hole in standard apertures was deprecated. Usage of low resolutions and trailing zero omission in the FS command was deprecated. The entire document was revised for clarity. The readability of the text was improved. The terminology was made consistent. The glossary was expanded. A number of additional images were added, including the Gerber file processing diagrams, command types diagram, aperture macro rotation illustration. Some of existing images were recreated to improve the quality. Several new tables were added to explain the relation between D code commands and graphics state parameters. The glossary was updated. The sections were rearranged. From now the revision numbering follows the year.month scheme as in 2015.06.

## 11.27 Revision J4 (2015 02)

The .AperFunction values "Slot", "CutOut" and "Cavity" were added. The text on standard attributes was made more explicit. An example of a poorly constructed plane was added.

-----

## 11.28 Revision J3 (2014 10)

The .FileFunction values for copper and drill layers were extended to contain more information about the complete job.

## 11.29 Revision J2 (2014 07)

Attaching aperture attributes with regions was much simplified. A section about numerical accuracy was added.

## 11.30 Revision J1 (2014 02) - X2

This revision created Gerber X2 by adding attributes to what was hitherto a pure image format. See chapter 5. X2 is Gerber version 2, with "X1" being Gerber version 1, without attributes. Gerber X2 is backward compatible as attributes do not affect image generation.

## 11.31 Revision I4 (2013 10)

The commands LN, IN and IP were deprecated. The possibility of re-assigning D codes was revoked. The regions overview section 4.10.1 was expanded and examples were added different places in 4.10 to further clarify regions. The chapters on command codes and syntax were restructured. The constraints on the thermal primitive parameters were made more explicit. Wording was improved in several places. The term '(mass) parameter' was replaced by 'extended command'.

## 11.32 Revision I3 (2013 06)

Questions about the order and precise effect of the deprecated commands MI, SF, OF, IR and AS were clarified. Coincident contour segments were explicitly defined.

## 11.33 Revision I2 (2013 04)

The "exposure on/off" parameter in macro apertures and the holes in standard apertures are sometimes incorrectly implemented. These features were explained in more detail. Readers and writers of Gerber files are urged to review their implementation in this light.

## 11.34 Revision I1 (2012 12)

**General. The entire specification was extensively reviewed for clarity. The document was re-**

organized, the text and the drawings have been improved and many new drawings were added.

**Deprecated elements. Elements of the format that are rarely used and superfluous or prone to**

misunderstanding have been deprecated. They are grouped together in the second part of this document. The first part contains the current format, which is clean and focused. We urge all *creators of Gerber files no longer to use deprecated elements of the format.*

**Graphics state and operation codes. The underlying concept of the graphics state and**

operation codes is now explicitly described. See section 2.3.2 and 2.3.2. We urge all providers *of Gerber software to review their implementation in the light of these sections.*

-----

**Defaults. In previous revisions the definitions of the default values for the modes were scattered**

throughout the text or were sometimes omitted. All default values are now unequivocally specified in an easy-to-read table. See 2.3.2. We urge all providers of Gerber software to review *their handling of defaults.*

**Rotation of macro primitives. The rotation center of macro primitives was clarified. See 4.5.3.**

*We urge providers of Gerber software to review their handling of the rotation of macro primitives.*

**G36/G37. The whole section is now much more specific. We urge providers of Gerber software**

*to review their contour generation in this light.*

**Coordinate data. Coordinate data without D01/D02/D03 in the same word can lead to**

confusion. It therefore has been deprecated. See 8.1.10. We urge all providers of Gerber *software to review their output of coordinate data in this light.*

**Maximum aperture number. In previous revisions the maximum aperture number was 999.**

This was insufficient for current needs and numerous files in the market use higher aperture numbers. We have therefore increased the limit to the largest number that fits in a signed 32-bit integer.

**Standard Gerber. Standard Gerber is revoked because it has many disadvantages and not a**

single advantage. We urge all users of Gerber software not to use Standard Gerber any longer.

**Incremental coordinates. These have been deprecated. Incremental coordinates lead to**

rounding errors. Do not use incremental coordinates.

**Name change: area and contour instead of polygon. Previous revisions contained an object**

called a polygon. This caused confusion between this object and a polygon aperture. These objects remain unchanged but are now called areas, defined by their contours. This does not alter the Gerber files.

**Name change: level instead of layer. Previous revisions of the specification contained a**

concept called a layer. These were often confused with PCB layers and have been renamed as levels. This is purely narrative and does not alter the Gerber files.
