# 7 Errors and Bad

## 7.1 Errors

Poor implementation of the Gerber format can give rise to invalid Gerber files or - worse - valid Gerber files that do not represent the intended image. The table below lists the most common errors.

###### Symptom

Standard Gerber or RS-274-D

Clearances in planes disappear.

Rotating aperture macros using primitive gives unexpected results.

Unexpected image after an aperture change or a D03.

Objects unexpectedly appear or disappear under holes in standard apertures.

Objects unexpectedly appear or disappear under holes in macro apertures.

Ucamco,

# Practices

###### Cause and Correct Usage

Standard Gerber is revoked and is therefore no *longer valid Gerber. It was designed for a* workflow that is as obsolete as the mechanical typewriter. It requires manual interpretation and is therefore error-prone. Do not use it. See 8.5.

###### Always use Gerber X!

This is often the consequence of invalid cut-ins resulting in self-intersecting contours. The root cause is usually sloppy rounding aggravated by low-resolution output. Use 6 digits precision. *See 4.10.3.* 21 Some CAD systems incorrectly assume that

primitive 21 rotates around its center. This is wrong, it rotates around the origin. *See 4.5.1.5.* Coordinates have been used without an explicit D01/D02/D03 operation code. This practice is deprecated because it leads to confusion about which operation code to use.

###### Coordinate data must always be combined with an explicit D01/D02/D03 operation code.

*See 8.1.10.* Some CAD systems incorrectly assume the hole in an aperture clears the underlying image. This is not so, the hole has no effect on the underlying image. *See 4.4.6.* Some systems incorrectly assume that exposure off in a macro aperture clears the underlying objects under the flash. This is wrong, exposure off creates a hole in the aperture and that hole has no effect on the image. *See 4.5.1.*

-----

| Polygons are smaller than expected. | Some CAD systems incorrectly assume the parameter of a Regular Polygon specifies the inside diameter. This is wrong: it specifies the outside diameter. See 4.4.5. |
|---|---|
| The result of the MI command is not as expected. | The MI command mirrors coordinate data but not apertures. A number of implementations unfortunately also mirror apertures making MI unsafe to use. It is therefore deprecated. Do not use the MI command but apply the transformation directly in the coordinate data. See 8.1.7. |
| The result of the SF command is not as expected. | The SF command scales coordinate data but no other sizes in the file. A number of implementations unfortunately also scale other elements making SF unsafe to use. It is therefore deprecated. Do not use the SF command but apply the transformation directly in the coordinate data. See 8.1.9 |
| A single Gerber file contains more than one image, separated by M00, M01 or M02 | This is invalid. A Gerber file can contain only one image. One file, one image. One image, one file. |
| Sending a PCB layer as several positive/negative files that must be merged together. | This is invalid in Gerber X. See 2.1. (It was valid in Standard Gerber but became obsolete with the introduction of LPD/LPC in Gerber X.) Apart from being invalid this obnoxious practice requires manual work and is error prone. One wonders why someone in his right mind would use this archaic method, which has a serious risk of scrap. One file = one layer |
| Strange error message. | Some files contain the strange pseudo command %ICAS*%. One wonders what this is supposed to achieve. Anyhow, it is invalid. |
| Error message; not the intended image. | Invalid format specification %FSD….*% The only valid zero omission options in the %FS are L and T. D is invalid. See 8.2.1.1. |
| Strange error message. | *Presence of %FSLAN2X26Y26*% The N2 in the format specification is invalid. See 4.1 One wonders what it is supposed to do. |
| Strange error message. | …X5555Y5555IJ001 Missing zero after the "I". The number after I must have at least one digit, see 13.5 |

*Reported errors*

-----

## 7.2 Bad Practices

Some Gerber files are syntactically correct but are needlessly cumbersome or error-prone. The table below summarizes common poor practices and gives the corresponding good practice.

###### Bad Practice

PCB fabrication data sets without proper profile layer.

Painted copper pours (aka stroking or vector-fill)

Painted pads (aka stroking or vector-fill)

Pads as contours instead of flashes

Ucamco,

###### Problems

The profile is an essential part of the fabrication data. It must be accurately defined, in a machinereadable manner. All too often only a drawing is provided, or corner marks, or the profile is only present merged in copper layers etc. Painted copper pours produce the intended image, but the file size explodes and getting rid of the painting require time consuming and error-prone manual work by CAM operators. Painting was needed for vector photoplotters in the 1960s and 1970s, devices now as outdated as the mechanical typewriter. There is not a single reason left to use painting. See painted copper pours above. Painting is even more damaging for pads, as the fabricator needs to know where the pads are, for example for electrical test. The only practical way to identify pads is to use flashes for pads exclusively. See above.

| PCB fabrication data sets | PCB fabrication data is complex |
|---|---|
| without netlist. | geometric data with an infinite number of variations. Differences in |

the interpretation of image data is very rare but does happen and then is costly. A netlist is a powerful check on the image data - it is akin to the redundancy checks used in all data transfer protocols. Omitting a netlist is omitting a basic security check.

###### Good Practice Always include a profile layer with a clean, accurately constructed profile. See 6.5

###### Always define copper pours with contours (G36/G37)

###### Always use flashed

**pads. Define pads,**

including SMD pads, with the AD and AM commands.

###### Always use flashed

**pads. Define pads,**

including SMD pads, with the AD and AM commands.

###### Always include a netlist in a PCB fabrication data set. A netlist can be provided in IPC-D- 356A file or with Gerber attributes - see 6.8.

-----

| Low resolution (number of | Poor registration of objects between |
|---|---|
| decimals in coordinates <6) | PCB layers; loss of accuracy; self- intersecting contours; invalid arcs; |

turning small arcs into full circles, missing clearances. Poor numerical accuracy is the main cause for errors in geometric software. Low resolution is the root cause for most problems with Gerber files and sometimes leads to scrap. Why would one use low resolution? To save a few bytes? It is sometimes argued some Gerber readers can only handle low resolutions. This may have been true in the distant past but it is no longer true now. Will you risk scrap to cater for a few probably mythical antiquated implementations? Cutting a single copper pour The information what the copper in pieces, typically through the pour is and where the clearances

| clearances to avoid regions | are is lost in a tangle of data, made |
|---|---|
| with holes. | worse by rounding error. The reader must attempt to laboriously reverse |

engineer the copper pour and clearances. Risk of scrap.

| Imprecisely positioned arc | An imprecisely positioned center |
|---|---|
| center points | makes the arc ambiguous and open to interpretation. This can lead to |

unexpected results. See 4.7.2 Non-standard file extensions When you use a non-standard file

extension the reader must open the file to know what format it is and which application to use. See 3.5.

| Writing files with deprecated | Each construct was deprecated for |
|---|---|
| constructs. | a reason. Many carry the risk of a misinterpretation. Continuing to use |

deprecated constructs is bad corporate citizenship as it blocks the industry from taking the next steps.

*Bad/good practices*

###### Use 6 decimals in

**mm units. See 4.1**

and 8.2.1.

**See section 4.10.5.**

###### Always position arc center points precisely.

###### Please use ".gbr" or ".GBR" as file extension for all your Gerber files. Generate files with current constructs

**only. (Note: it is OK**

for readers to handle deprecated constructs to cater for legacy files.)

-----
