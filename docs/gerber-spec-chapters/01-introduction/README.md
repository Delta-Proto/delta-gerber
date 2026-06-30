# 1 Introduction

## 1.1 Scope and Target Audience

This document specifies the Gerber layer format, a UTF-8 format for representing PCB fabrication data. The Gerber format is the de facto standard in the printed circuit board (PCB). This specification is intended for developers and users of Gerber software. A basic knowledge of PCB design or fabrication is assumed, and knowledge about PCB CAD/CAM is helpful.

## 1.2 Further Resources

The Ucamco website contains articles about the use of the Gerber format as well as sample files. For more information about Gerber or Ucamco see www.ucamco.com, or mail to [gerber@ucamco.com](mailto:gerber@ucamco.com) Ucamco strives to make this specification easy to read and unequivocal. If you find a part of this specification unclear, please ask. Your question will be answered, and it will be considered to improve this document. We are grateful for any suggestions for improvement.

## 1.3 Reference Gerber Viewer

Ucamco provides a reference Gerber file viewer - free of charge - at gerber-viewer.ucamco.com. The Reference Gerber Viewer provides an easy-to-use reference for both X1 and X2 Gerber files. - the utmost care was taken to display valid Gerber files correctly. It gives a clear warning on risky errors. It is a convenient complement to the written specification. (The specification has precedence if it conflicts with the viewer.) The Reference Gerber Viewer is an easy tool to review PCB fabrication data. For completeness, it also displays drill files in XNC, Excellon and other NC formats as well as netlists in IPC-D-356. If X2 is used the layer structure is displayed, and the function of all objects can be checked - e.g. whether a drill hole is a via or a component hole. As the Reference Gerber Viewer is a cloud-based online web service there is no software to download, install and maintain -it is always up to date. It is simple and easy to learn. It offers the following benefits:

- *For developers, it provides an easy way to test their Gerber output and to answer*

questions about the interpretation of the specification.

- *For users of Gerber files, it provides an easy way to check the file they have received or*

are about to send, and to settle discussions about the interpretation of a file. *You are allowed to integrate a link to the online reference viewer in your website. Email us a* [*gerber@ucamco.com for more information.*](mailto:gerber@ucamco.com)

-----

Ucamco owns copyrights in this document. All rights reserved. No part of this document or its content may be re-distributed, reproduced or published, modified or not, translated or not, in any form or in any way, electronically, mechanically, by print or any other means without prior written permission from Ucamco. One reason Ucamco must retain its copyright in the Gerber Format® specification is to maintain the integrity of the standard. The information contained herein is subject to change without prior notice. Revisions may be issued from time to time. This document supersedes all previous versions. Users of the Gerber Format®, especially software developers, must consult www.ucamco.com to determine whether new revisions were issued. Ucamco developed the Gerber Format®. All intellectual property contained in this document is solely owned by Ucamco. By publishing this document Ucamco has not granted any license to or alienated any rights in the intellectual property contained in it. Gerber Format® is a Ucamco registered trademark. By using the name Gerber this document or developing software interfaces based on this format, users agree, not to (i) rename the Gerber Format®; (ii) associate the Gerber Format® with data that does not conform to the Gerber format specification; (iii) develop derivative versions, modifications, or extensions without prior written approval by Ucamco; (iv) make alternative interpretations of the data; (v) communicate that the Gerber Format® is not owned by Ucamco, explicitly or implied. Whatever other conditions may apply, by using the name Gerber Format®, or the intellectual property contained in this document, developers of software interfaces for the Gerber Format® , agree to make a reasonable effort to comply with the latest revision of the specification, including the Conformance in section 2.12 to safeguard the integrity of the standard. The material, information and instructions are provided AS IS without warranty of any kind, either express or implied. Ucamco does not warrant, or makes any representations regarding, the use of the information contained herein, the results of its use, non-infringement, merchantability, or fitness for a particular purpose. Ucamco shall not be liable for any direct, indirect, consequential, or incidental damages arising out of the use or inability to use the information contained herein. You are solely responsible for determining the appropriateness of using this information and assume any risks associated with it. All product names cited are trademarks or registered trademarks of their respective owners.

-----

## Ucamco,
