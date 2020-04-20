# IGResourceGenerator
Implementation Guide Resource Generator
***
This process drives of the spreadsheet at https://docs.google.com/spreadsheets/d/1E7ps-euW93GN4f5L61rOQAuK6RH8x69ZXAkD2i0VDS0/
##Getting started 
```
>1 Download the project.
>2 create the output directory (e.g. mkdir output).
>3 $java -jar dist/IGResourceGenerator-1.0-RELEASE-all.jar
```

##Basic use
```
usage: java -jar dist/IGResourceGenerator-1.0-RELEASE-all.jar
 -b, --bundle <filename>       create in a single bundle file 
 -h,--help                     print this help text
 -i,--id <id>                  the id of the items(s) to generate
 -od,--output <directory>      the directory where generated output should be placed
 -p,--prefix <prefix>          a prefix for the generated file
 -q,--quit                     runs with no normal output
 -s,--suffix <suffix>          a suffix for the generated file
 -t,--type <type>              the type of the items(s) to generate
 -td,--templates <directory>   the directory in which source templates are located
 -v,--verbose                  runs with verbose output
```

The first time the application is run a web browser should launch and generate authentication information for access to the spreadsheet.

##Directories
Note: The default directory locations can be overriden by using the appropriate command line options.
###Templates
All templates are located by default in the directory: **templates**. Each file here is an 
Apache Velocity template (see https://velocity.apache.org/engine/1.7/user-guide.html for more information).
There is a mapping table spreadsheet, under the sheet "TypeMapping" that relates the Type to the Template file to use to generate that type. 

###Output
When the generator is run all output is placed in the directory: ***output**. This directory should be created prior to using the tool.


