package io.github.lemyst.enfusion.objfmt.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes;

%%

%public
%class _EnfusionObjectLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

// A newline is a REAL TOKEN here, not whitespace, and that is the central fact about this format.
// A property is `key value*` with no terminator, and a value may be a bare identifier
// (`halign center_ref`), so `position 0 0` followed by `size 1 1` is ambiguous unless the line
// break is something the parser can see. Everything else about the grammar follows from this.
EOL           = \r\n | \r | \n
WHITE_SPACE   = ([ \t\f] | \uFEFF)+

// Vanilla layouts carry no comments at all, but hand-written ones do -- the engine tolerates them
// and this mod's own layouts use them to explain non-obvious widget geometry.
LINE_COMMENT  = "//" [^\r\n]*
BLOCK_COMMENT = "/*" ([^*] | "*"+ [^*/])* "*"+ "/"

// `\\.` matches any escaped character, as in the script lexer: layout text runs contain markup such
// as `<image set=\"playstation_buttons\">`, and an unknown escape must never become a BAD_CHARACTER.
STRING        = \" ([^\"\\\r\n] | \\[^\r\n])* \"

// The sign belongs to the number. There is no arithmetic in this format, so a separate minus token
// would buy nothing and would let `position -10 0` lex as three values instead of two.
NUMBER        = [+-]? ([0-9]+ ("." [0-9]*)? | "." [0-9]+) ([eE] [+-]? [0-9]+)?

IDENT         = [A-Za-z_] [A-Za-z0-9_]*

%%

<YYINITIAL> {
  {EOL}               { return EnfusionObjectTypes.EOL; }
  {WHITE_SPACE}       { return TokenType.WHITE_SPACE; }

  {LINE_COMMENT}      { return EnfusionObjectTypes.LINE_COMMENT; }
  {BLOCK_COMMENT}     { return EnfusionObjectTypes.BLOCK_COMMENT; }

  "{"                 { return EnfusionObjectTypes.LBRACE; }
  "}"                 { return EnfusionObjectTypes.RBRACE; }

  {STRING}            { return EnfusionObjectTypes.STRING; }
  {NUMBER}            { return EnfusionObjectTypes.NUMBER; }
  {IDENT}             { return EnfusionObjectTypes.IDENT; }
}

[^] { return TokenType.BAD_CHARACTER; }
