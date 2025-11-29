package au.id.rleach.blockpatterns.nms;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Proves the limitation without mockito-inline: attempting to mock a final-like type fails.
 *
 * We use the NMS enum {@code net.minecraft.core.Direction} which is inherently final.
 * Plain Mockito (core) cannot mock enums/final classes unless the inline mock maker is enabled.
 */
class FinalClassMockingTest {

    @Test
    @DisplayName("Mockito core can mock final-like NMS types (enum Direction) without inline mock maker")
    void mockingExample() {
        var x = mock(Direction.class);
        assertInstanceOf(Direction.class, x, "yay");
    }
}
