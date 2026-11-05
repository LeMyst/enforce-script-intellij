package io.github.lemyst.enfusion.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.psi.EnfusionTypes;

%%

%public
%class _EnfusionLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

// A stray BOM is folded into whitespace: some vanilla files carry one and it must not become a
// BAD_CHARACTER.
WHITE_SPACE = ([ \t\f\r\n] | \uFEFF)+

DIGITS      = [0-9]+
EXP         = [eE] [+-]? [0-9]+
HEX_LIT     = 0 [xX] [0-9a-fA-F]+
// Scientific notation never occurs in the corpus; lexed anyway since it costs nothing.
FLOAT_LIT   = ({DIGITS} "." [0-9]* {EXP}? [fF]? | "." {DIGITS} {EXP}? [fF]? | {DIGITS} {EXP} [fF]? | {DIGITS} [fF])
DEC_LIT     = {DIGITS}

// `\\.` matches ANY escaped character on purpose. The corpus contains `"dz\\characters\\zombies\data\\..."`
// where `\d` is not a defined escape and the compiler passes it through literally. Never emit a
// BAD_CHARACTER inside a string.
//
// The closing quote is optional, so an unclosed string runs to the end of the line as a STRING
// rather than leaving a lone `"` as a BAD_CHARACTER. Valid code is unaffected: the longest match
// still takes the closing quote. It is what lets the quote handler see the `"` just typed as the
// start of a string and insert its partner; without it, every string typed showed a parse error
// until it was closed. EnfusionUnclosedStringAnnotator reports the unclosed case instead.
STRING      = \" ([^\"\\\r\n] | \\[^\r\n])* \\? \"?

LINE_DOC      = ("//!" | "///") [^\r\n]*
LINE_COMMENT  = "//" [^\r\n]*
BLOCK_DOC     = ("/**" | "/*!") ([^*] | "*"+ [^*/])* "*"+ "/"
BLOCK_COMMENT = "/*" ([^*] | "*"+ [^*/])* "*"+ "/"

// The only five directives that exist. `#` appears nowhere else outside string literals (the
// `"#STR_BR_..."` form is matched by STRING first, which is longer), so no line anchor is needed.
PP_DIRECTIVE = "#" ("ifdef" | "ifndef" | "else" | "endif" | "define") [^\r\n]*

IDENT = [A-Za-z_] [A-Za-z0-9_]*

%%

<YYINITIAL> {
  {WHITE_SPACE}       { return TokenType.WHITE_SPACE; }

  {PP_DIRECTIVE}      { return EnfusionTypes.PP_DIRECTIVE; }

  {LINE_DOC}          { return EnfusionTypes.LINE_DOC_COMMENT; }
  {LINE_COMMENT}      { return EnfusionTypes.LINE_COMMENT; }
  {BLOCK_DOC}         { return EnfusionTypes.BLOCK_DOC_COMMENT; }
  {BLOCK_COMMENT}     { return EnfusionTypes.BLOCK_COMMENT; }

  // ---- hard keywords (must precede IDENT: equal-length match resolves to the earlier rule) ----
  "class"             { return EnfusionTypes.CLASS_KW; }
  "enum"              { return EnfusionTypes.ENUM; }
  "typedef"           { return EnfusionTypes.TYPEDEF; }
  "new"               { return EnfusionTypes.NEW; }
  "delete"            { return EnfusionTypes.DELETE; }
  "return"            { return EnfusionTypes.RETURN; }
  "if"                { return EnfusionTypes.IF; }
  "else"              { return EnfusionTypes.ELSE; }
  "for"               { return EnfusionTypes.FOR; }
  "foreach"           { return EnfusionTypes.FOREACH; }
  "while"             { return EnfusionTypes.WHILE; }
  "switch"            { return EnfusionTypes.SWITCH; }
  "case"              { return EnfusionTypes.CASE; }
  "default"           { return EnfusionTypes.DEFAULT; }
  "break"             { return EnfusionTypes.BREAK; }
  "continue"          { return EnfusionTypes.CONTINUE; }
  "null"              { return EnfusionTypes.NULL; }
  "this"              { return EnfusionTypes.THIS; }
  "super"             { return EnfusionTypes.SUPER; }
  "true"              { return EnfusionTypes.TRUE; }
  "false"             { return EnfusionTypes.FALSE; }

  "void"              { return EnfusionTypes.VOID; }
  "int"               { return EnfusionTypes.INT; }
  "float"             { return EnfusionTypes.FLOAT; }
  "bool"              { return EnfusionTypes.BOOL; }
  "string"            { return EnfusionTypes.STRING_TYPE; }
  "vector"            { return EnfusionTypes.VECTOR; }
  "typename"          { return EnfusionTypes.TYPENAME; }
  "func"              { return EnfusionTypes.FUNC; }

  "ref"               { return EnfusionTypes.REF; }
  "autoptr"           { return EnfusionTypes.AUTOPTR; }
  "static"            { return EnfusionTypes.STATIC; }
  "const"             { return EnfusionTypes.CONST; }
  "private"           { return EnfusionTypes.PRIVATE; }
  "protected"         { return EnfusionTypes.PROTECTED; }
  "override"          { return EnfusionTypes.OVERRIDE; }
  "proto"             { return EnfusionTypes.PROTO; }
  "native"            { return EnfusionTypes.NATIVE; }
  "notnull"           { return EnfusionTypes.NOTNULL; }
  "out"               { return EnfusionTypes.OUT; }
  "inout"             { return EnfusionTypes.INOUT; }
  "volatile"          { return EnfusionTypes.VOLATILE; }

  // `modded sealed extends local auto owned reference external event Class NULL` are deliberately
  // NOT keywords here -- they stay IDENT and are matched contextually. See EnfusionTypes.

  {STRING}            { return EnfusionTypes.STRING; }
  {HEX_LIT}           { return EnfusionTypes.INT_LITERAL; }
  {FLOAT_LIT}         { return EnfusionTypes.FLOAT_LITERAL; }
  {DEC_LIT}           { return EnfusionTypes.INT_LITERAL; }

  {IDENT}             { return EnfusionTypes.IDENT; }

  // ---- multi-character operators before their prefixes ----
  "<<"                { return EnfusionTypes.LTLT; }
  "&&"                { return EnfusionTypes.ANDAND; }
  "||"                { return EnfusionTypes.OROR; }
  "=="                { return EnfusionTypes.EQEQ; }
  "!="                { return EnfusionTypes.NEQ; }
  "<="                { return EnfusionTypes.LE; }
  ">="                { return EnfusionTypes.GE; }
  "+="                { return EnfusionTypes.PLUS_EQ; }
  "-="                { return EnfusionTypes.MINUS_EQ; }
  "*="                { return EnfusionTypes.STAR_EQ; }
  "/="                { return EnfusionTypes.SLASH_EQ; }
  "&="                { return EnfusionTypes.AMP_EQ; }
  "|="                { return EnfusionTypes.PIPE_EQ; }
  "++"                { return EnfusionTypes.PLUSPLUS; }
  "--"                { return EnfusionTypes.MINUSMINUS; }

  // NOTE: there is deliberately no ">>" rule. `array<ref array<PIXEL>>` must lex as GT GT so the
  // nested type-argument lists close naturally; shift-right is rebuilt in the parser from two
  // adjacent GT tokens. `>>=` does not exist in the language, so there is no third case.

  "{"                 { return EnfusionTypes.LBRACE; }
  "}"                 { return EnfusionTypes.RBRACE; }
  "("                 { return EnfusionTypes.LPAREN; }
  ")"                 { return EnfusionTypes.RPAREN; }
  "["                 { return EnfusionTypes.LBRACKET; }
  "]"                 { return EnfusionTypes.RBRACKET; }
  ";"                 { return EnfusionTypes.SEMICOLON; }
  ","                 { return EnfusionTypes.COMMA; }
  "."                 { return EnfusionTypes.DOT; }
  ":"                 { return EnfusionTypes.COLON; }
  "="                 { return EnfusionTypes.EQ; }
  "+"                 { return EnfusionTypes.PLUS; }
  "-"                 { return EnfusionTypes.MINUS; }
  "*"                 { return EnfusionTypes.STAR; }
  "/"                 { return EnfusionTypes.SLASH; }
  "%"                 { return EnfusionTypes.PERCENT; }
  "!"                 { return EnfusionTypes.BANG; }
  "~"                 { return EnfusionTypes.TILDE; }
  "&"                 { return EnfusionTypes.AMP; }
  "|"                 { return EnfusionTypes.PIPE; }
  "^"                 { return EnfusionTypes.CARET; }
  "<"                 { return EnfusionTypes.LT; }
  ">"                 { return EnfusionTypes.GT; }
}

// `'` is not a literal delimiter in this language -- there are zero character literals in the
// corpus -- so flagging it catches a common modder mistake.
[^]                   { return TokenType.BAD_CHARACTER; }
