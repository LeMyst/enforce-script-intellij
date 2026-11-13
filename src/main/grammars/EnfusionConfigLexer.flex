package io.github.lemyst.enfusion.cfg.lexer;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes;

%%

%public
%class _EnfusionConfigLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

// Newlines are ordinary whitespace here, unlike the `.layout` format. Every construct in a config is
// terminated by a brace or a semicolon, so nothing depends on where the line breaks fall -- which
// removes the one thing that made the object grammar delicate.
WHITE_SPACE   = ([ \t\f\r\n] | \uFEFF)+

LINE_COMMENT  = "//" [^\r\n]*
BLOCK_COMMENT = "/*" ([^*] | "*"+ [^*/])* "*"+ "/"

// An unclosed comment runs to the end of the file, as in the script lexer; see the comment on
// UNCLOSED_COMMENT in EnfusionLexer.flex. EnfusionUnclosedCommentAnnotator reports it.
UNCLOSED_COMMENT = "/*" ([^*] | "*"+ [^*/])* "*"*

// `#define` appears in exactly two files of the 377-file corpus and nothing else preprocessor-ish
// appears at all -- no `#include`, no `#ifdef`. Vanilla's configs are de-rapified from `config.bin`
// and are already flat. So the directive is lexed to end of line and handed to the parser as a
// comment-class token; there is no preprocessing lexer on this side and none is needed.
PP_DIRECTIVE  = "#" [^\r\n]*

// **A backslash is not an escape here**, and getting that wrong is not a small matter: paths in this
// format are backslash-separated and routinely end in one, so `"DZ\gear\drinks\data\"` is a complete
// string whose last character is a backslash. Treating `\"` as an escaped quote swallowed the closing
// quote and derailed the rest of the file -- that is what broke four vanilla and Expansion configs,
// including one where 1,191 of 1,473 classes went missing.
//
// The only escape is `""`, Arma's own doubled quote. Longest-match then makes `"a""b"` a single
// string and leaves `{"", ""}` as two, which is the pair the rule has to get right.
STRING        = \" ([^\"\r\n] | \"\")* \"

// Scientific notation is not exotic here: vanilla configs are full of values like `9.9999997e-05`,
// written that way by the de-rapifier. The sign belongs to the number for the same reason as in the
// object lexer -- there is no arithmetic in this format.
NUMBER        = [+-]? ([0-9]+ ("." [0-9]*)? | "." [0-9]+) ([eE] [+-]? [0-9]+)?

IDENT         = [A-Za-z_] [A-Za-z0-9_]*

// A name may start with digits: vanilla `DZ/sounds/hpp/config.cpp` declares test tones such as
// `class 1kHz_mono_1s_SoundShader`, and the engine accepts them. Without this the digits lexed as a
// NUMBER and the class failed to parse. The rule needs a letter or `_` after the digits, so a plain
// number never matches it, and it is listed after NUMBER, so a tie such as `1e5`, which both match
// in full, stays a number.
DIGIT_IDENT   = [0-9]+ [A-Za-z_] [A-Za-z0-9_]*

%%

<YYINITIAL> {
  {WHITE_SPACE}       { return TokenType.WHITE_SPACE; }

  {LINE_COMMENT}      { return EnfusionConfigTypes.LINE_COMMENT; }
  {BLOCK_COMMENT}     { return EnfusionConfigTypes.BLOCK_COMMENT; }
  {UNCLOSED_COMMENT}  { return EnfusionConfigTypes.BLOCK_COMMENT; }
  {PP_DIRECTIVE}      { return EnfusionConfigTypes.PP_DIRECTIVE; }

  "class"             { return EnfusionConfigTypes.CLASS_KW; }

  "{"                 { return EnfusionConfigTypes.LBRACE; }
  "}"                 { return EnfusionConfigTypes.RBRACE; }
  "["                 { return EnfusionConfigTypes.LBRACKET; }
  "]"                 { return EnfusionConfigTypes.RBRACKET; }
  "+="                { return EnfusionConfigTypes.PLUS_EQ; }
  "-="                { return EnfusionConfigTypes.MINUS_EQ; }
  "="                 { return EnfusionConfigTypes.EQ; }
  ";"                 { return EnfusionConfigTypes.SEMICOLON; }
  ":"                 { return EnfusionConfigTypes.COLON; }
  ","                 { return EnfusionConfigTypes.COMMA; }

  {STRING}            { return EnfusionConfigTypes.STRING; }
  {NUMBER}            { return EnfusionConfigTypes.NUMBER; }
  {IDENT}             { return EnfusionConfigTypes.IDENT; }
  {DIGIT_IDENT}       { return EnfusionConfigTypes.IDENT; }
}

[^] { return TokenType.BAD_CHARACTER; }
