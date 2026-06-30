# 3 Syntax

## 3.1 Character Set

A Gerber file is expressed in UTF-8 Unicode. The new line characters CR and LF are allowed at the end of words - after '\*' - or commands - after '%'. Their use is encouraged to increase human readability. The Gerber file is therefore human-readable and transferrable between systems. The characters '\*' and '%' are delimiters and can only be used as prescribed in the syntax. Space characters are only allowed inside strings and fields (see 3.4.3 and 3.4.4). Gerber files are case-sensitive. Command codes must be in upper case. Actually, except for user defined attribute values, all characters in a Gerber file are restricted to the readable ASCII characters, with codes 32 to 126, and new line characters. User defined meta-data may require special characters such as μ or may be in other languages than English; hence UTF-8 Unicode is allowed.

## 3.2 Formal Grammar

The formal grammar used in this specification is the parsing expression grammar (PEG), similar in formalism to context-free grammars, with a somewhat different interpretation. See [https://en.wikipedia.org/wiki/Parsing\_expression\_grammar for more information. The grammar of](https://en.wikipedia.org/wiki/Parsing_expression_grammar) is expressed in the variant of the Extended Backus-Naur Form used by the TatSu PEG parser generator. https://tatsu.readthedocs.io/en/stable/ for more information. Only a subset of the rules in the very powerful TatSu grammar is needed - after all Gerber is a simple format. Below is a description of that subset, taken from the TatSu documentation. A grammar consists of a sequence of one or more rules of the form:

```
name = <expression> ;
```

The expressions are constructed from the following operators, in reverse order of precedence.

-----

| Rule | Grammar Syntax Rules Name | Grammar Syntax Rules Description |
|---|---|---|
| # | Comment | Comments have no effect on the grammar. |
| e1 \| e2 | Choice | Match either e1 or e2 A \| can be used before the first option as a layout aid: rule = \| option1 \| option2 \| option3 ; |
| (e) | Grouping | Match e, making it a node in the syntax tree |
| [e] | Option | Optionally match e |
| {e} or {e}* | Closure | Match e zero or more times. |
| {e}+ | Positive closure | Match e one or more times. |
| &e | Positive lookahead | Succeeds if e can be parsed. Does not consume input |
| !e | Negative lookahead | Fails if e can be parsed. Does not consume input |
| 'text' | Token | Match the token text. If text is alphanumeric it will only parse if the character following the token is not alphanumeric. This is done to prevent tokens like IN matching when the text ahead is INITIALIZE. This feature can be turned off by setting the grammar directive @@nameguard=False |
| /regexp/ | Regex | The pattern expression. Match the regular expression regexp at the current text position. Python style regex is used, and it is interpreted as a Python raw string. |
| $ | End-of-text | Verify that the end of the input text has been reached. |

-----

|  | Grammar Directives |
|---|---|
| Directive | Description |
| @@grammar :: <word | Specifies the name of the grammar. |
| @@nameguard :: <bool> | When set to True, avoids matching tokens when the next character in the input sequence is alphanumeric. Defaults to True. See the 'text' expression for an explanation. @@nameguard :: False |
| @@whitespace :: <regexp> | Provides a regular expression for the whitespace to be ignored by the parser. It defaults to /(?s)\\s+/ @@whitespace :: /[\\t ]+ |

## 3.3 Commands

Commands are the core syntactic element of the Gerber format. A Gerber file is a stream of commands. Commands define the graphics state, create graphical objects, defines apertures, manage attributes and so on. Commands are built with words, the basic syntactic building block of a Gerber file. A word is a non-empty character string, excluding the reserved characters '\*' and '%', terminated with an '\*'

free\_character = /[^%\*]/; # All characters but \* and % word = {free\_character}+ '\*';

For historic reasons, there are two command syntax styles: word commands and extended commands.

command =

| extended\_command | word\_command ; word\_command = word; extended\_command = '%' {word}+ '%';

Word commands are identified by a command code, the letter G, D or M followed by a positive integer, e.g. G02. Most word commands only consist of the command code, some also contain coordinates. Extended commands are identified by a two-character command code that is followed by parameters specific to the code. An extended command is enclosed by a pair of '%' delimiters. An overview of all commands is in section 2 .8, a full description in chapters 3.5 and 5. The example below shows a stream of Gerber commands. Word commands are in yellow, extended commands in green.

###### Example:

```
G04 Different command styles*
%FSLAX26Y26*%
%MOMM*%
%AMDonut*
1,1,$1,$2,$3*
$4=$1x0.75*
1,0,$4,$2,$3*
```

-----

## Ucamco,

```
%
%ADD11Donut,0.30X0X0*%
%ADD10C,0.1*%
G75*
G02*
D10*
X0Y0D02*
X2000000Y0I1000000J0D01*
D11*
X0Y2000000D03*
M02*
```

One of the strengths of the Gerber format is its human readability. Use line breaks to enhance readability; put one word or command per line.

-----

## 3.4 Data Types

All data types are tokens in the Gerber syntax, expressed a regex.

### Integers

Integers must fit in a 32-bit signed integer. Examples:

```
0
-1024
+16
```

| unsigned_integer | = | /[0-9]+/; |
|---|---|---|
| positive_integer | = | /[0-9]*[1-9][0-9]*/; |
| integer | = | /[+-]?[0-9]+/; |

### Decimals

Decimals must fit in an IEEE double. Examples:

```
-200
5000
1234.56
.123
-0.128
0
```

unsigned\_decimal = /((([0-9]+)(\\.[0-9]\*)?)|(\\.[0-9]+))/; decimal = /[+-]?((([0-9]+)(\\.[0-9]\*)?)|(\\.[0-9]+))/;

Note that a standalone comma ',' is not a valid decimal point.

### Strings

Strings are used for communication between humans and can contain other characters than ASCII. These are expressed by UTF-8 literal characters, or by a Unicode escape. Note that reserved characters must be escaped.

string = /[^%\*]\*/; # All characters except \*%

Note that UTF-8 is identical to ASCII for any character that can be represented by ASCII. The Unicode escape for the copyright symbol '©' is as follows:

- With lower case u for a 16-bit hex value: `\\u00A9`
- With upper case U for a 32-bit hex value: `\\U000000A9` Zero-fill if needed to reach the required 4 or 8 hex digits
- The reserved characters '%' (\\u0025 ) and '\*' (\\u002A) must always be escaped as they

are the delimiters of the Gerber syntax.

- '\\' (\\u005C) must be escaped as it is the escape character.
- ',' (\\u002C) separates fields, and therefore must be escaped in any string that does not

end the word.

-----

Any character may be escaped. Escape every non-ASCII character if you need to keep a file ASCII-only. This may increase compatibility with legacy software but defeats human readability of the meta-data. If you want to keep the Gerber file printable ASCII-only use escape sequences for any character in strings or fields. This may increase compatibility with legacy software but defeats human readability of the meta-data.

### Fields

The fields follow the string syntax in section 3 .4.3 with the additional restriction that a field must not contain commas. Fields are intended to represent comma-separated items in strings. A comma can be escaped with \\u002C.

field = /[^%\*,]\*/; # All characters except \*%,

### Names

Names identify something, such as an attribute. They are for use only within the Gerber format and are therefore limited to printable ASCII. Names consist of upper- or lower-case ASCII letters, underscores ('\_'), dots ('.'), a dollar sign ('$') and digits. The first character cannot be a digit. Names are from 1 to 127 characters long. Names beginning with a dot '.' are reserved for standard names defined in the specification. User defined names cannot begin with a dot.

Name = [.\_$a-zA-Z][.\_$a-zA-Z0-9]{0,126} StandardName = \\.[.\_$a-zA-Z][.\_$a-zA-Z0-9]{0,125} UserDefinedName = [\_$a-zA-Z][\_.$a-zA-Z0-9]{0,126}

The scope of a name is from its definition till the end of the file. Names are case-sensitive. Names for macro variables used in AM commands are more restrictive. They are of the form $n, with n a positive integer, for example $3.

-----

Ucamco

"

## 3.5 Grammar of the Gerber Layer Format

| @@grammar | :: | Gerber_2022.02 |
|---|---|---|
| @@nameguard | :: | False |
| @@whitespace | :: | /\\n/ |

start = {

| G04 | MO | FS | AD | AM | Dnn | G75 | G01 | G02 | G03 | D01 | D02 | D03 | LP | LM | LR | LS | region\_statement | AB\_statement | SR\_statement | TF | TA | TO | TD }\* M02 $;

-----

# Graphics commands #------------------

G04 = 'G04' string '\*';

MO = '%MO' ('MM'|'IN') '\*%'; FS = '%FS' 'LA' 'X' coordinate\_digits 'Y' coordinate\_digits '\*%'; coordinate\_digits = /[1-6][6]/;

G01 = 'G01\*'; G02 = 'G02\*'; G03 = 'G03\*'; G75 = 'G75\*';

AD = '%AD'

aperture\_identifier (

| 'C' ',' ~ decimal ['X' decimal] | 'R' ',' ~ decimal 'X' decimal ['X' decimal] | 'O' ',' ~ decimal 'X' decimal ['X' decimal] | 'P' ',' ~ decimal 'X' decimal ['X' decimal ['X' decimal]] | name [',' decimal {'X' decimal}\*] ) '\*%'; AM = '%AM' name '\*' macro\_body '%'; macro\_body = { primitive | variable\_definition }+; variable\_definition = macro\_variable '=' expr '\*'; primitive =

| '0' string '\*' | '1' ',' expr ',' expr ',' expr ',' expr [',' expr] '\*' | '20' ',' expr ',' expr ',' expr ',' expr ',' expr ',' expr ',' expr '\*'

| '21' ',' expr ',' expr ',' expr ',' expr ',' expr ',' expr '\*' | '4' ',' expr ',' expr ',' expr ',' expr {',' expr ',' expr}+ ',' expr'\*'

| '5' ',' expr ',' expr ',' expr ',' expr ',' expr ',' expr '\*' | '7' ',' expr ',' expr ',' expr ',' expr ',' expr ',' expr '\*' ; macro\_variable = /\\$[0-9]\*[1-9][0-9]\*/; expr =

|{/[+-]/ term}+ |expr /[+-]/ term

|term

; term = |term /[x\\/]/ factor |factor ; factor =

| '(' ~ expr ')'

-----

Ucamco,

|macro\_variable |unsigned\_decimal ;

Dnn = aperture\_identifier '\*';

D01 = ['X' integer] ['Y' integer] ['I' integer 'J' integer] 'D01\*';

| D02 = ['X' | integer] | ['Y' | integer] | 'D02*'; |
|---|---|---|---|---|
| D03 = ['X' | integer] | ['Y' | integer] | 'D03*'; |

LP = '%LP' ('C'|'D') '\*%'; LM = '%LM' ('N'|'XY'|'Y'|'X') '\*%';

| LR | = '%LR' | decimal | '*%'; |
|---|---|---|---|
| LS | = '%LS' | decimal | '*%'; |

M02 = 'M02\*';

-----

Ucamco,

region\_statement = G36 {contour}+ G37; contour = D02 {D01|G01|G02|G03}\*; G36 = 'G36\*'; G37 = 'G37\*';

| AB_statement = AB_open | block | AB_close; |
|---|---|---|
| AB_open AB_close = '%AB' '*%'; | = | '%AB' aperture_identifier '*%'; |

SR\_statement = SR\_open block SR\_close; SR\_open = '%SR' 'X' positive\_integer 'Y' positive\_integer

'I' decimal 'J' decimal '\*%'; SR\_close = '%SR' '\*%';

block = {

| G04 | MO | FS | AD | AM | Dnn | D01 | D02 | D03 | G01 | G02 | G03 | G75 | LP | LM | LR | LS | region\_statement | AB\_statement

| TF | TA | TO | TD }\* ;

-----

Ucamco,

# Attribute commands #-------------------

TF = '%TF' file\_attribute\_name {',' field}\* '\*%'; TA = '%TA' aperture\_attribute\_name {',' field}\* '\*%';

| TO | = '%TO' | object_attribute_name | {',' | field}* | '*%'; |
|---|---|---|---|---|---|
| TD | = '%TD' |  |  |  |  |

[

| file\_attribute\_name | aperture\_attribute\_name | object\_attribute\_name | user\_name ] '\*%';

file\_attribute\_name =

| '.Part' | '.FileFunction' | '.FilePolarity' | '.SameCoordinates' | '.CreationDate' | '.GenerationSoftware' | '.ProjectId' | '.MD5' | user\_name ; aperture\_attribute\_name =

| '.AperFunction' | '.DrillTolerance' | '.FlashText' | user\_name ; object\_attribute\_name =

| '.N' | '.P' | '.C' &',' # To avoid this rule also parses .CRot etc | '.CRot' | '.CMfr' | '.CMPN' | '.CVal' | '.CMnt' | '.CFtp' | '.CPgN' | '.CPgD' | '.CHgt' | '.CLbN' | '.CLbD' | '.CSup' | user\_name ;

-----

Ucamco,

# Tokens, by regex #----------------- unsigned\_integer positive\_integer integer unsigned\_decimal decimal

= /[0-9]+/; = /[0-9]\*[1-9][0-9]\*/; = /[+-]?[0-9]+/; = /((([0-9]+)(\\.[0-9]\*)?)|(\\.[0-9]+))/; = /[+-]?((([0-9]+)(\\.[0-9]\*)?)|(\\.[0-9]+))/;

aperture\_identifier = /D[0]\*[1-9][0-9]+/;

name = /[.\_a-zA-Z$][.\_a-zA-Z0-9]\*/; user\_name = /[\_a-zA-Z$][.\_a-zA-Z0-9]\*/; # Cannot start with a dot

| string | = /[^%*]*/; # All characters except * % |
|---|---|
| field | = /[^%*,]*/; # All characters except * % , |

-----

## 3.6 File Extension, MIME Type and UTI

The Gerber Format has a standard file name extension, a registered mime type and a UTI definition.

###### Standard file extension: .gbr or .GBR

**Mime type:** application/vnd.gerber

(see http://www.iana.org/assignments/media-types/application/vnd.gerber)

###### Mac OS X UTI:

```
<key>UTExportedTypeDeclarations</key>
<array>
<dict>
<key>UTTypeIdentifier</key>
<string>com.ucamco.gerber.image</string>
<key>UTTypeReferenceURL</key>
<string>http://www.ucamco.com/gerber</string>
<key>UTTypeDescription</key>
<string>Gerber image</string>
<key>UTTypeConformsTo</key>
<array>
<string>public.plain-text</string>
<string>public.image</string>
</array>
<key>UTTypeTagSpecification</key>
<dict>
<key>public.filename-extension</key>
<array>
<string>gbr</string>
</array>
<key>public.mime-type</key>
<string>application/vnd.gerber</string>
</dict>
</dict>
</array>
```

-----

Ucamco.
