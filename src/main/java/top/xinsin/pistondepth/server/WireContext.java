package top.xinsin.pistondepth.server;

import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;

/**
 * 当前服务端线程正在求值的红石粉坐标。递归更新会嵌套，因此使用线程局部栈：
 * 子求值结束后 pop，父求值的坐标就恢复，不会把子线误记为后续事件来源。
 */
public final class WireContext {
    private static final ThreadLocal<ArrayDeque<BlockPos>> CURRENT = ThreadLocal.withInitial(ArrayDeque::new);

    private WireContext() { }

    public static void push(BlockPos pos) {
        // BlockPos 可能是可变坐标，先固定下来再放入上下文。
        CURRENT.get().push(pos.immutable());
    }

    public static void pop() {
        ArrayDeque<BlockPos> stack = CURRENT.get();
        stack.pop();
        if (stack.isEmpty()) CURRENT.remove();
    }

    public static BlockPos current() {
        return CURRENT.get().peek();
    }
}
