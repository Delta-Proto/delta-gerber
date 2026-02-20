# KiCad DRC Templates

Here you can find the KiCad templates we have created for 'Simple' and 'Advanced' designs. These are empty KiCad board files that have HQ NextPCB's constraints and other information already configured.

### Simple

Use the simple file settings for simple and cost-sensitive designs. These settings are designed to avoid additional manufacturing costs as much as possible within the scope of KiCad’s board settings and prefers suggested values over the limits of NextPCB’s manufacturing capabilities. This is suitable for most two and four layer designs.

### Advanced

Use the advanced file settings for space-constrained and advanced designs that include fine-pitch components and high signal counts, for example. These are set to the limits of NextPCB’s [manufacturing capabilities](https://www.nextpcb.com/pcb-capabilities) (within reason) which could incur additional costs. This is suitable for complex multilayer designs. Notably, the allowed trace widths and drill holes are smaller.



## How to Import Board Settings in KiCad

The following steps were created using KiCad 8.0.

1. Open your KiCad Board (.kicad_pcb) file and go to File -> Board Setup... There is also a shortcut button on the toolbar.

<img src="https://github.com/user-attachments/assets/35827923-f07c-4c5c-8b4f-67f7298e8aef" style="width:30%; height:auto;">
<img src="https://github.com/user-attachments/assets/ee0e1529-7c3d-4d7e-b64f-c4beba3139fe" style="width:40%; height:auto;">

<br>
<br>

2. Click the Import Settings from Another Board... button on the bottom left of the new window.

<img src="https://github.com/user-attachments/assets/b767f571-0a99-4452-9f2c-d7565b6ac581" style="width:70%; height:auto;">

3. In the new window, choose the .kicad_pcb file that has the settings you wish to copy in the Import from: field. Below, you can pick and choose the specific settings to import. This is useful if you only need to copy over a few select settings. If you want to copy everything from the Board Setup menu, there is a Select All button on the bottom which will check all the boxes. 

<img src="https://github.com/user-attachments/assets/a23a60ac-b805-4c04-b87a-cd4654305029" style="width:40%; height:auto;">

4. Then just click the Import Settings button and wait for KiCad to import the settings. Once successfully imported, the Import Settings and loading window will close. Confirm the new settings have been copied over and then click the OK button to close the setup and apply the changes.

## Main Changes

**Board Stackup:** Some user layers removed for clarity. Too many additional layers can cause confusion, and important information is more likely to be overlooked. For multilayer boards, the inner layers have been renamed according to the total number of copper layers, where the first copper layer is the front copper layer. If this nomenclature is unfamiliar, feel free to ignore it.

A6 layer board for example:

F.Cu    &nbsp; &nbsp;->    F.Cu

In1.Cu   ->    GL2.Cu

In2.Cu  ->    GL3.Cu

In3.Cu  ->    GL4.Cu

In4.Cu  ->    GL5.Cu

B.Cu    &nbsp; &nbsp;->    B.Cu

**Text & Graphics:** Changes to the silkscreen graphics and text default thickness.

**Net Classes:** Small adjustments were made to the Net Classes.

**Design Rules - Constraints:** Values changed to best match HQ NextPCB’s production capabilities. The Simple file contains settings suitable for most basic designs. Follow these constraints if you want to avoid additional costs as much as possible. The Advanced version has the limits of our capabilities set for maximum freedom. More capabilities can be found here: https://www.nextpcb.com/pcb-capabilities

**Pre-defined Sizes:** We have provided a selection of pre-configured dimensions for your convenience. Most track widths are in mils for greater precision. 

## About HQ Electronics and NextPCB

Founded in 2011, Shenzhen Huaqiu (HQ) Electronics Co. Ltd., is the world's leading industrial digital manufacturing platform for PCB manufacture, PCB assembly, components distribution, and electronics design verification tools. Their passion for reliable, fast and cost-effective electronics manufacturing services has transformed the industry, streamlining the entire production process and allowing production efficiency and cost-savings to reach record highs without compromising on reliability.

Serving national engineers for over 15 years, HQ Electronics has becoming a household name in providing full-feature multilayer PCBs and PCB assembly services engineers can trust. HQ NextPCB, HQ Electronics's international division, brings these capabilities, speed, affordability and more to clients in over 160 countries.

www.nextpcb.com
