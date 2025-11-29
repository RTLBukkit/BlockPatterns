package au.id.rleach.blockpatterns.nms;

import com.google.common.cache.LoadingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockPatternTest {

    // hack to access protected static method
    static class Bridge extends BlockPattern {

        public Bridge(Predicate<BlockInWorld>[][][] pattern) {
            super(pattern);
        }

        public static BlockPos translateAndRotate(BlockPos pos, Direction forwards, Direction up, int aisle, int shelf, int book){
            return BlockPattern.translateAndRotate(pos, forwards, up, aisle, shelf, book);
        }

    }

    @Mock BlockInWorld blockInWorldRed;
    @Mock BlockInWorld blockInWorldBlue;
    @Mock BlockInWorld blockInWorldGreen;
    @Mock BlockInWorld blockInWorldBlack;
    @Mock LoadingCache<BlockPos, BlockInWorld> loadingCache;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    @DisplayName("translateAndRotate: invalid finger/up combination throws")
    void testInvalidCombinationThrows() {
        BlockPos origin = new BlockPos(0, 64, 0);
        assertThrows(IllegalArgumentException.class, () ->
            Bridge.translateAndRotate(origin, Direction.UP, Direction.UP, 0, 0, 0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            Bridge.translateAndRotate(origin, Direction.NORTH, Direction.SOUTH, 0, 0, 0)
        );
    }


    @Test
    @DisplayName("Spec out 'FrontLeftTop, Forwards and Top")
    void testFrontLeftTopForwardsAndTop() {
        BlockPos frontLeftTop = new BlockPos(0, 0, 0);

        BlockPos blockPos = Bridge.translateAndRotate(
                frontLeftTop,
                Direction.EAST,
                Direction.UP,
                3,
                4,
                5
        );

        assertEquals( 5, blockPos.getX());
        assertEquals(-4, blockPos.getY());
        assertEquals( 3, blockPos.getZ());
    }

    @Test
    @DisplayName("todo")
    void testFrontLeftTopForwardsAndDown() {
        BlockPos frontLeftTop = new BlockPos(0, 0, 0);
        BlockPos red = new BlockPos(4, 0, 0);
        BlockPos green = new BlockPos(0, -3, 0);
        BlockPos blue = new BlockPos(0, 0, 2);

        BlockPattern.BlockPatternMatch blockPatternMatch = new BlockPattern.BlockPatternMatch(frontLeftTop, Direction.EAST, Direction.UP, loadingCache, 4, 3, 2);
        when(loadingCache.getUnchecked(frontLeftTop)).thenReturn(blockInWorldBlack);
        when(loadingCache.getUnchecked(red)).thenReturn(blockInWorldRed);
        when(loadingCache.getUnchecked(blue)).thenReturn(blockInWorldBlue);
        when(loadingCache.getUnchecked(green)).thenReturn(blockInWorldGreen);
        assertEquals(blockInWorldBlack, blockPatternMatch.getBlock(0, 0, 0));
        assertEquals(blockInWorldRed, blockPatternMatch.getBlock(0, 0, 4));
        assertEquals(blockInWorldBlue, blockPatternMatch.getBlock(2, 0, 0));
        assertEquals(blockInWorldGreen, blockPatternMatch.getBlock(0, 3, 0));
    }

    public static class AxisPos implements ArgumentMatcher<BlockPos> {

        private Direction dir;

        public AxisPos(Direction dir) {
            this.dir = dir;
        }

        @Override
        public boolean matches(BlockPos blockPos) {
            if ((blockPos.getX() == 0 && blockPos.getY() == 0 && blockPos.getZ() == 0)) return dir == null;
            Vec3i cross = dir.getUnitVec3i().cross(blockPos);
            return cross.getX() == 0 && cross.getY() == 0 && cross.getZ() == 0;
        }
    }

    @Test
    @DisplayName("testing")
    void testTesting() {
        Predicate<BlockInWorld> r = x -> x == blockInWorldRed ;
        Predicate<BlockInWorld> g = x -> x == blockInWorldGreen ;
        Predicate<BlockInWorld> b = x -> x == blockInWorldBlue ;
        Predicate<BlockInWorld> o = x -> x == blockInWorldBlack ;

        LevelReader levelReader = mock(LevelReader.class);

        when(levelReader.getBlockState(ArgumentMatchers.argThat(new AxisPos(null))))
                .thenReturn(BLACK_CONCRETE);
        when(levelReader.getBlockState(ArgumentMatchers.argThat(new AxisPos(Direction.EAST))))
                .thenReturn(RED_CONCRETE);
        when(levelReader.getBlockState(ArgumentMatchers.argThat(new AxisPos(Direction.DOWN))))
                .thenReturn(GREEN_CONCRETE);
        when(levelReader.getBlockState(ArgumentMatchers.argThat(new AxisPos(Direction.SOUTH))))
                .thenReturn(BLUE_CONCRETE);
        when(levelReader.getBlockState(ArgumentMatchers.any()))
                .thenReturn(AIR);

        BlockPattern patternUnderTest = BlockPatternBuilder
                .start()
                .aisle("ORRRR", "G    ", "G    ", "G    ")
                .aisle("B    ", "     ", "     ", "     ")
                .aisle("B    ", "     ", "     ", "     ")
                .where('R', r)
                .where('B', b)
                .where('G', g)
                .where('O', o)
                .build();

        BlockPattern.BlockPatternMatch matches = patternUnderTest.matches(levelReader, new BlockPos(0, 0, 0), Direction.EAST, Direction.UP);
        assertNotNull(matches);
    }
}
