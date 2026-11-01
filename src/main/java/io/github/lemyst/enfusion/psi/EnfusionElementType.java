package io.github.lemyst.enfusion.psi;

import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.lang.EnfusionLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class EnfusionElementType extends IElementType {
    public EnfusionElementType(@NotNull @NonNls String debugName) {
        super(debugName, EnfusionLanguage.INSTANCE);
    }
}
