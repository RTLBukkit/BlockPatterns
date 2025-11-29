package au.id.rleach.blockpatterns.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BlockPatternBuilder} using a simple shape definition.
 * <p>
 * The test focuses on verifying that the builder correctly interprets the
 * provided aisles and derives the right dimensions (width x height x depth).
 * We purposefully use permissive predicates (always-true) for all symbols so
 * the test does not require a world instance to perform matching; we only
 * validate the pattern’s structural metadata here.
 */
public class BlockPatternTest {

    @Test
    void buildsPatternWithExpectedDimensions_fromGivenAisles() {
        // Define permissive predicates for each symbol used in the aisles
        // (including space). We only care about the geometry in this test.
        Predicate<BlockInWorld> any = biw -> true;

        // Provided example (3 aisles deep, each 4 rows tall). The longest row is 5 chars wide.
        BlockPattern patternUnderTest = BlockPatternBuilder
            .start()
            .aisle(
                "ORRRR", // row 0
                "G    ", // row 1
                "G    ", // row 2
                "G    "  // row 3
            )
            .aisle(
                "B    ",
                "     ",
                "     ",
                "     "
            )
            .aisle(
                "B    ",
                "     ",
                "     ",
                "     "
            )
            // Map each used symbol to a predicate. Space should also be defined.
            .where('O', any)
            .where('R', any)
            .where('G', any)
            .where('B', any)
            .where(' ', any)
            .build();

        // Expect width=5 (max row length), height=4 (rows per aisle), depth=3 (aisles provided)
        assertEquals(5, patternUnderTest.getWidth(), "Pattern width should be 5");
        assertEquals(4, patternUnderTest.getHeight(), "Pattern height should be 4");
        assertEquals(3, patternUnderTest.getDepth(), "Pattern depth should be 3");
    }

    @Test
    void matchesFakeWorldWithAxisMapping_andRealPredicates() {
        // --- Define the aisles (depth 3) as provided ---
        String[][] aisles = new String[][]{
            {"ORRRR", "G    ", "G    ", "G    "},
            {"B    ", "     ", "     ", "     "},
            {"B    ", "     ", "     ", "     "}
        };

        BlockPattern blockPattern = BlockPatternBuilder
            .start()
            .aisle(aisles[0])
            .aisle(aisles[1])
            .aisle(aisles[2])
            .where('O', colorIs(Color.BLACK))
            .where('R', colorIs(Color.RED))
            .where('G', colorIs(Color.GREEN))
            .where('B', colorIs(Color.BLUE))
            .where(' ', biw -> true)
            .build();

        assertEquals(5, blockPattern.getWidth());
        assertEquals(4, blockPattern.getHeight());
        assertEquals(3, blockPattern.getDepth());

        // ZYX (just like the block pattern internals when facing East)
        final Color[][][] world = new Color[12][12][12];
        {
            for (Color[][] z : world) {
                for (Color[] y : z) {
                    Arrays.fill(y, Color.AIR);
                }
            }
            world[6][6][6] = Color.BLACK;

            world[6][6][7] = Color.RED;
            world[6][6][8] = Color.RED;
            world[6][6][9] = Color.RED;
            world[6][6][10] = Color.RED;

            world[6][5][6] = Color.GREEN;
            world[6][4][6] = Color.GREEN;
            world[6][3][6] = Color.GREEN;

            world[7][6][6] = Color.BLUE;
            world[8][6][6] = Color.BLUE;
        }

        ColorAccessor fakeLevel = (ColorAccessor) mock(
                LevelReader.class,
                withSettings().extraInterfaces(ColorAccessor.class)
        );
        when(fakeLevel.colorAt(any(BlockPos.class)))
            .thenAnswer(invocation -> {
                        BlockPos pos = invocation.getArgument(0);
                        return world[pos.getZ()][pos.getY()][pos.getX()];
                    }
            );

        BlockPattern.BlockPatternMatch matches = blockPattern.matches((LevelReader) fakeLevel, new BlockPos(6, 6, 6), Direction.EAST, Direction.UP);
        assertNotNull(matches);
        assertEquals(new BlockPos(6, 6, 6), matches.getFrontTopLeft());
        assertEquals(Direction.EAST, matches.getForwards());
        assertEquals(Direction.UP, matches.getUp());

        assertEquals(5, matches.getWidth());
        assertEquals(4, matches.getHeight());
        assertEquals(3, matches.getDepth());

        world[6][6][6] = Color.AIR;
        matches = blockPattern.matches((LevelReader) fakeLevel, new BlockPos(6, 6, 6), Direction.EAST, Direction.UP);
        assertNull(matches);
    }


    // --- Test-only support types ---
    enum Color { BLACK, RED, GREEN, BLUE, AIR }

    interface ColorAccessor {
        Color colorAt(BlockPos pos);
    }

    private static Predicate<BlockInWorld> colorIs(Color expected) {
        return biw -> {
            LevelReader lvl = biw.getLevel();
            // The test configures lvl to also implement ColorAccessor
            ColorAccessor ca = (ColorAccessor) lvl;
            Color actual = ca.colorAt(biw.getPos());
            return actual == expected;
        };
    }
}
