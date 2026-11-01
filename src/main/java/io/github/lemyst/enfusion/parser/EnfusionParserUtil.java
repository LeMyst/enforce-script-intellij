package io.github.lemyst.enfusion.parser;

import com.intellij.lang.PsiBuilder;
import com.intellij.lang.parser.GeneratedParserUtilBase;
import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.psi.EnfusionTypes;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Bounded-lookahead predicates backing the {@code <<...>>} external rules in Enfusion.bnf.
 *
 * <p>Grammar-Kit's ordered choice could technically disambiguate declarations on its own, but it
 * would re-parse type references at every member and produce unreadable error messages. These
 * predicates decide in one linear scan and consume nothing.
 */
public class EnfusionParserUtil extends GeneratedParserUtilBase {

    /**
     * Modifiers that are lexed as IDENT because they collide with plausible identifiers.
     * `auto` is absent on purpose: it occupies the type slot, not the modifier slot.
     * Must match the contextual entries of {@code modifier} in Enfusion.bnf: a word missing here
     * makes {@link #methodAhead} misread {@code sealed void Foo()} as a field of type {@code sealed}.
     */
    private static final Set<String> CONTEXTUAL_MODIFIERS = new HashSet<>(
            Arrays.asList("local", "owned", "reference", "external", "event", "sealed"));

    /** Matches an IDENT with exact text, so a contextual keyword can be used in the grammar. */
    public static boolean contextual(PsiBuilder b, int level, String text) {
        if (b.getTokenType() != EnfusionTypes.IDENT) return false;
        if (!text.equals(b.getTokenText())) return false;
        b.advanceLexer();
        return true;
    }

    /**
     * Rebuilds shift-right from two GT tokens that are physically adjacent. The lexer never emits
     * `>>` so that `array<ref array<PIXEL>>` closes two nested type-argument lists; this is the
     * other half of that trade. `>>=` arrives as GT GE and is handled by {@link #shiftAssign}.
     */
    public static boolean adjacentGtGt(PsiBuilder b, int level) {
        if (b.getTokenType() != EnfusionTypes.GT) return false;
        int expectedSecond = b.getCurrentOffset() + 1;
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        boolean ok = b.getTokenType() == EnfusionTypes.GT && b.getCurrentOffset() == expectedSecond;
        if (!ok) {
            m.rollbackTo();
            return false;
        }
        b.advanceLexer();
        m.drop();
        return true;
    }

    /**
     * Matches a `>` that is NOT the first half of a shift-right.
     *
     * <p>`relExpr` has a lower priority than `shiftExpr`, so without this guard it consumes the
     * first `>` of `c1 >> 8` and then fails looking for an operand. The same goes for the first
     * `>` of `>>=`, which lexes as GT GE.
     */
    public static boolean singleGt(PsiBuilder b, int level) {
        if (b.getTokenType() != EnfusionTypes.GT) return false;
        int expectedSecond = b.getCurrentOffset() + 1;
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        IElementType next = b.getTokenType();
        boolean adjacent = (next == EnfusionTypes.GT || next == EnfusionTypes.GE)
                && b.getCurrentOffset() == expectedSecond;
        m.rollbackTo();
        if (adjacent) return false;
        b.advanceLexer();
        return true;
    }

    /**
     * The compound shifts {@code <<=} and {@code >>=}, as in {@code bit <<= 1}
     * (1_core/proto/enconvert.c).
     *
     * <p>Neither is a token. {@code <<=} lexes as LTLT EQ, and {@code >>=} as GT GE because
     * {@code >>} is never lexed (see {@link #adjacentGtGt}). Each is rebuilt from a physically
     * adjacent pair, so {@code a << = b} stays an error.
     */
    public static boolean shiftAssign(PsiBuilder b, int level) {
        IElementType first = b.getTokenType();
        IElementType second;
        int firstLength;
        if (first == EnfusionTypes.LTLT) {
            second = EnfusionTypes.EQ;
            firstLength = 2;
        } else if (first == EnfusionTypes.GT) {
            second = EnfusionTypes.GE;
            firstLength = 1;
        } else {
            return false;
        }
        int expectedSecond = b.getCurrentOffset() + firstLength;
        PsiBuilder.Marker m = b.mark();
        b.advanceLexer();
        boolean ok = b.getTokenType() == second && b.getCurrentOffset() == expectedSecond;
        if (!ok) {
            m.rollbackTo();
            return false;
        }
        b.advanceLexer();
        m.drop();
        return true;
    }

    /**
     * True when the position is a generic instantiation used as a static qualifier, as in
     * {@code JsonFileLoader<AutotestConfigJson>.LoadFile(path, data, err)}.
     *
     * <p>Requires a balanced {@code <...>} followed immediately by {@code .}, which is what stops
     * an ordinary comparison such as {@code a < b} from being mistaken for one.
     */
    public static boolean genericQualifierAhead(PsiBuilder b, int level) {
        PsiBuilder.Marker m = b.mark();
        boolean ok = false;
        if (b.getTokenType() == EnfusionTypes.IDENT) {
            b.advanceLexer();
            if (b.getTokenType() == EnfusionTypes.LT) {
                int depth = 0;
                boolean closed = false;
                while (true) {
                    IElementType t = b.getTokenType();
                    if (t == null || t == EnfusionTypes.SEMICOLON || t == EnfusionTypes.LBRACE
                            || t == EnfusionTypes.RBRACE || t == EnfusionTypes.LPAREN
                            || t == EnfusionTypes.RPAREN) {
                        break;
                    }
                    if (t == EnfusionTypes.LT) depth++;
                    else if (t == EnfusionTypes.GT) depth--;
                    b.advanceLexer();
                    if (depth == 0) {
                        closed = true;
                        break;
                    }
                }
                ok = closed && b.getTokenType() == EnfusionTypes.DOT;
            }
        }
        m.rollbackTo();
        return ok;
    }

    /**
     * True when the position starts a method declaration rather than a field: attributes, then
     * modifiers, then a type, then an optional `~` (destructor), then a name, then `(`.
     */
    public static boolean methodAhead(PsiBuilder b, int level) {
        PsiBuilder.Marker m = b.mark();
        boolean ok = scanDeclHead(b) && b.getTokenType() == EnfusionTypes.LPAREN;
        m.rollbackTo();
        return ok;
    }

    /**
     * True when the position starts a local variable declaration rather than an expression
     * statement. Separates `Foo bar;` and `int inds[n] = {..};` from `foo.Bar();`.
     */
    public static boolean localVarDeclAhead(PsiBuilder b, int level) {
        PsiBuilder.Marker m = b.mark();
        boolean ok = scanDeclHead(b);
        if (ok) {
            IElementType t = b.getTokenType();
            ok = t == EnfusionTypes.SEMICOLON
                    || t == EnfusionTypes.EQ
                    || t == EnfusionTypes.COMMA
                    || t == EnfusionTypes.LBRACKET;
        }
        m.rollbackTo();
        return ok;
    }

    /** Consumes `attributeList? modifier* typeRef TILDE? IDENT`, leaving the builder just past it. */
    private static boolean scanDeclHead(PsiBuilder b) {
        skipAttributes(b);
        skipModifiers(b);
        if (!skipTypeRef(b)) return false;
        if (b.getTokenType() == EnfusionTypes.TILDE) b.advanceLexer();
        if (b.getTokenType() != EnfusionTypes.IDENT) return false;
        b.advanceLexer();
        return true;
    }

    private static void skipAttributes(PsiBuilder b) {
        while (b.getTokenType() == EnfusionTypes.LBRACKET) {
            int depth = 0;
            do {
                IElementType t = b.getTokenType();
                if (t == null) return;
                if (t == EnfusionTypes.LBRACKET) depth++;
                else if (t == EnfusionTypes.RBRACKET) depth--;
                b.advanceLexer();
            } while (depth > 0);
        }
    }

    private static void skipModifiers(PsiBuilder b) {
        while (true) {
            IElementType t = b.getTokenType();
            if (isHardModifier(t)) {
                b.advanceLexer();
            } else if (t == EnfusionTypes.IDENT && CONTEXTUAL_MODIFIERS.contains(b.getTokenText())) {
                b.advanceLexer();
            } else {
                return;
            }
        }
    }

    private static boolean isHardModifier(IElementType t) {
        return t == EnfusionTypes.REF || t == EnfusionTypes.AUTOPTR || t == EnfusionTypes.STATIC
                || t == EnfusionTypes.CONST || t == EnfusionTypes.PRIVATE
                || t == EnfusionTypes.PROTECTED || t == EnfusionTypes.OVERRIDE
                || t == EnfusionTypes.PROTO || t == EnfusionTypes.NATIVE
                || t == EnfusionTypes.NOTNULL || t == EnfusionTypes.OUT
                || t == EnfusionTypes.INOUT || t == EnfusionTypes.VOLATILE;
    }

    private static boolean isTypeNameStart(IElementType t) {
        return t == EnfusionTypes.IDENT || t == EnfusionTypes.INT || t == EnfusionTypes.FLOAT
                || t == EnfusionTypes.BOOL || t == EnfusionTypes.STRING_TYPE
                || t == EnfusionTypes.VECTOR || t == EnfusionTypes.VOID
                || t == EnfusionTypes.TYPENAME || t == EnfusionTypes.FUNC
                || t == EnfusionTypes.CLASS_KW;
    }

    /** Skips `Name<...>` (any nesting depth) plus any number of trailing `[]` pairs. */
    private static boolean skipTypeRef(PsiBuilder b) {
        if (!isTypeNameStart(b.getTokenType())) return false;
        b.advanceLexer();

        if (b.getTokenType() == EnfusionTypes.LT) {
            int depth = 0;
            do {
                IElementType t = b.getTokenType();
                // Bail out on anything that cannot appear inside a type-argument list, so a
                // comparison such as `a < b;` can never be mistaken for an unterminated generic.
                if (t == null || t == EnfusionTypes.SEMICOLON || t == EnfusionTypes.LBRACE
                        || t == EnfusionTypes.RBRACE || t == EnfusionTypes.LPAREN
                        || t == EnfusionTypes.RPAREN) {
                    return false;
                }
                if (t == EnfusionTypes.LT) depth++;
                else if (t == EnfusionTypes.GT) depth--;
                b.advanceLexer();
            } while (depth > 0);
        }

        while (b.getTokenType() == EnfusionTypes.LBRACKET) {
            PsiBuilder.Marker m = b.mark();
            b.advanceLexer();
            if (b.getTokenType() != EnfusionTypes.RBRACKET) {
                // `int inds[numInds]` -- an extent on the declarator, not part of the type.
                m.rollbackTo();
                break;
            }
            m.drop();
            b.advanceLexer();
        }
        return true;
    }
}
