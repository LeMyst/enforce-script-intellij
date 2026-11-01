package io.github.lemyst.enfusion.objfmt.psi;

import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.objfmt.EnfusionObjectLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class EnfusionObjectTokenType extends IElementType {
    public EnfusionObjectTokenType(@NotNull @NonNls String debugName) {
        super(debugName, EnfusionObjectLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "EnfusionObjectTokenType." + super.toString();
    }
}
