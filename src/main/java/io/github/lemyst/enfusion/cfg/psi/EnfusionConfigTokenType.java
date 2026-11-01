package io.github.lemyst.enfusion.cfg.psi;

import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.cfg.EnfusionConfigLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class EnfusionConfigTokenType extends IElementType {
    public EnfusionConfigTokenType(@NotNull @NonNls String debugName) {
        super(debugName, EnfusionConfigLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "EnfusionConfigTokenType." + super.toString();
    }
}
