lexer grammar ProceduralSqlLexer;

@header {
package ru.it_spectrum.ai.jdbc.mcp.antlr;
}

SELECT: S E L E C T;
WITH: W I T H;
INSERT: I N S E R T;
UPDATE: U P D A T E;
DELETE: D E L E T E;
MERGE: M E R G E;

LPAREN: '(';
RPAREN: ')';
SEMICOLON: ';';

SINGLE_QUOTED_STRING: '\'' ('\'' '\'' | ~'\'')* '\'';
DOUBLE_QUOTED_IDENTIFIER: '"' ('"' '"' | ~'"')* '"';
BRACKET_QUOTED_IDENTIFIER: '[' ~']'* ']';
IDENTIFIER: [a-zA-Z_][a-zA-Z_0-9$#@]*;
LINE_COMMENT: '--' ~[\r\n]* -> channel(HIDDEN);
BLOCK_COMMENT: '/*' .*? '*/' -> channel(HIDDEN);
WHITESPACE: [ \t\r\n]+ -> channel(HIDDEN);

OTHER: .;

fragment A: [aA];
fragment B: [bB];
fragment C: [cC];
fragment D: [dD];
fragment E: [eE];
fragment F: [fF];
fragment G: [gG];
fragment H: [hH];
fragment I: [iI];
fragment J: [jJ];
fragment K: [kK];
fragment L: [lL];
fragment M: [mM];
fragment N: [nN];
fragment O: [oO];
fragment P: [pP];
fragment Q: [qQ];
fragment R: [rR];
fragment S: [sS];
fragment T: [tT];
fragment U: [uU];
fragment V: [vV];
fragment W: [wW];
fragment X: [xX];
fragment Y: [yY];
fragment Z: [zZ];
