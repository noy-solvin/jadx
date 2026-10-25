package jadx.core.dex.visitors.regions.maker;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.EdgeInsnAttr;
import jadx.core.dex.attributes.nodes.LoopInfo;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.SwitchInsn;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.InsnContainer;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.regions.Region;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.blocks.BlockSet;
import jadx.core.utils.exceptions.JadxOverflowException;

import static jadx.core.utils.BlockUtils.getNextBlock;

public class RegionMaker {
	private final MethodNode mth;
	private final RegionStack stack;

	private final IfRegionMaker ifMaker;
	private final LoopRegionMaker loopMaker;

	private final BlockSet processedBlocks;
	private final int[] blockDupCount;
	private final int regionsLimit;

	private static final int MAX_BLOCK_DUPLICATIONS = 5;
	private static final int MAX_REGION_DEPTH = 300;
	private int makeRegionDepth;
	private int regionsCount;

	public RegionMaker(MethodNode mth) {
		this.mth = mth;
		this.stack = new RegionStack(mth);
		this.ifMaker = new IfRegionMaker(mth, this);
		this.loopMaker = new LoopRegionMaker(mth, this, ifMaker);
		this.processedBlocks = BlockSet.empty(mth);
		this.blockDupCount = new int[mth.getBasicBlocks().size()];
		this.regionsLimit = mth.getBasicBlocks().size() * 400;
	}

	public Region makeMthRegion() {
		return makeRegion(mth.getEnterBlock());
	}

	Region makeRegion(BlockNode startBlock) {
		Objects.requireNonNull(startBlock);
		Region region = new Region(stack.peekRegion());
		if (stack.containsExit(startBlock)) {
			insertEdgeInsns(region, startBlock);
			return region;
		}
		// Issue #2979: Guard against unbounded recursion depth in irreducible control flow
		if (makeRegionDepth > MAX_REGION_DEPTH) {
			mth.addWarnComment("Max region recursion depth reached at block " + startBlock);
			return region;
		}
		if (processedBlocks.addChecked(startBlock)) {
			// Issue #2979: Bound block duplications to prevent infinite duplication loops
			int pos = startBlock.getPos();
			if (pos >= 0 && pos < blockDupCount.length) {
				if (++blockDupCount[pos] > MAX_BLOCK_DUPLICATIONS) {
					mth.addWarnComment("Duplication limit reached for block: " + startBlock);
					return region;
				}
			}
			if (!startBlock.contains(AFlag.DUPLICATED)) {
				mth.addWarnComment("Code duplicated, block: " + startBlock + ' ' + startBlock.getAttributesString());
				startBlock.add(AFlag.DUPLICATED);
			}
		}
		makeRegionDepth++;
		try {
			BlockSet visited = BlockSet.empty(mth);
			visited.add(startBlock);
			BlockNode next = startBlock;
			while (next != null) {
				next = traverse(region, next);
				if (next != null) {
					// Issue #2979: Guard against cyclic transitions in sequential region traversal
					if (visited.addChecked(next)) {
						mth.addWarnComment("Cycle detected in region at block: " + next);
						break;
					}
				}
				regionsCount++;
				if (regionsCount > regionsLimit) {
					throw new JadxOverflowException("Regions count limit reached at block " + startBlock);
				}
			}
		} finally {
			makeRegionDepth--;
		}
		return region;
	}

	/**
	 * Recursively traverse all blocks from 'block' until block from 'exits'
	 */
	private @Nullable BlockNode traverse(Region r, BlockNode block) {
		if (block.contains(AFlag.MTH_EXIT_BLOCK)) {
			return null;
		}
		BlockNode next = null;
		boolean processed = false;

		List<LoopInfo> loops = block.getAll(AType.LOOP);
		int loopCount = loops.size();
		if (loopCount != 0 && block.contains(AFlag.LOOP_START)) {
			if (loopCount == 1) {
				next = loopMaker.process(r, loops.get(0), stack);
				processed = true;
			} else {
				for (LoopInfo loop : loops) {
					if (loop.getStart() == block) {
						next = loopMaker.process(r, loop, stack);
						processed = true;
						break;
					}
				}
			}
		}

		InsnNode insn = BlockUtils.getLastInsn(block);
		if (!processed && insn != null) {
			switch (insn.getType()) {
				case IF:
					next = ifMaker.process(r, block, (IfNode) insn, stack);
					processed = true;
					break;

				case SWITCH:
					SwitchRegionMaker switchMaker = new SwitchRegionMaker(mth, this);
					next = switchMaker.process(r, block, (SwitchInsn) insn, stack);
					processed = true;
					break;

				case MONITOR_ENTER:
					SynchronizedRegionMaker syncMaker = new SynchronizedRegionMaker(mth, this);
					next = syncMaker.process(r, block, insn, stack);
					processed = true;
					break;
			}
		}
		if (!processed) {
			r.add(block);
			next = getNextBlock(block);
		}
		if (next != null && !stack.containsExit(block) && !stack.containsExit(next)) {
			return next;
		}
		return null;
	}

	private void insertEdgeInsns(Region region, BlockNode exitBlock) {
		List<EdgeInsnAttr> edgeInsns = exitBlock.getAll(AType.EDGE_INSN);
		if (edgeInsns.isEmpty()) {
			return;
		}
		List<InsnNode> insns = new ArrayList<>(edgeInsns.size());
		addOneInsnOfType(insns, edgeInsns, InsnType.BREAK);
		addOneInsnOfType(insns, edgeInsns, InsnType.CONTINUE);
		region.add(new InsnContainer(insns));
	}

	private void addOneInsnOfType(List<InsnNode> insns, List<EdgeInsnAttr> edgeInsns, InsnType insnType) {
		for (EdgeInsnAttr edgeInsn : edgeInsns) {
			InsnNode insn = edgeInsn.getInsn();
			if (insn.getType() == insnType) {
				insns.add(insn);
				return;
			}
		}
	}

	RegionStack getStack() {
		return stack;
	}

	boolean isProcessed(BlockNode block) {
		return processedBlocks.contains(block);
	}

	void clearBlockProcessedState(BlockNode block) {
		processedBlocks.remove(block);
		// Issue #2979: Reset duplication counter so LoopRegionMaker retains full loop bodies
		int pos = block.getPos();
		if (pos >= 0 && pos < blockDupCount.length) {
			blockDupCount[pos] = 0;
		}
	}
}
