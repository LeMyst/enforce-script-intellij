package io.github.lemyst.enfusion.psi;

import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.lang.EnfusionLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class EnfusionTokenType extends IElementType {
    public EnfusionTokenType(@NotNull @NonNls String debugName) {
        super(debugName, EnfusionLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "EnfusionTokenType." + super.toString();
    }
}
