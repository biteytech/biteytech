/*
 * Copyright 2022 biteytech@protonmail.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tech.bitey.dataframe;

import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterator.SORTED;
import static tech.bitey.bufferstuff.BufferUtils.EMPTY_BIG_BUFFER;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import tech.bitey.bufferstuff.BigByteBuffer;
import tech.bitey.bufferstuff.BufferBitSet;
import tech.bitey.bufferstuff.SmallIntBuffer;

final class NonNullInstantColumn extends NonNullSingleBufferColumn<Instant, InstantColumn, NonNullInstantColumn>
		implements InstantColumn {

	static final Map<Integer, NonNullInstantColumn> EMPTY = new HashMap<>();
	static {
		EMPTY.computeIfAbsent(NONNULL_CHARACTERISTICS, c -> new NonNullInstantColumn(EMPTY_BIG_BUFFER, 0, 0, c, false));
		EMPTY.computeIfAbsent(NONNULL_CHARACTERISTICS | SORTED,
				c -> new NonNullInstantColumn(EMPTY_BIG_BUFFER, 0, 0, c, false));
		EMPTY.computeIfAbsent(NONNULL_CHARACTERISTICS | SORTED | DISTINCT,
				c -> new NonNullInstantColumn(EMPTY_BIG_BUFFER, 0, 0, c, false));
	}

	static NonNullInstantColumn empty(int characteristics) {
		return EMPTY.get(characteristics | NONNULL_CHARACTERISTICS);
	}

	private final SmallIntBuffer elements;

	NonNullInstantColumn(BigByteBuffer buffer, int offset, int size, int characteristics, boolean view) {
		super(buffer, offset, size, characteristics, view);

		this.elements = buffer.asIntBuffer();
	}

	@Override
	NonNullInstantColumn construct(BigByteBuffer buffer, int offset, int size, int characteristics, boolean view) {
		return new NonNullInstantColumn(buffer, offset, size, characteristics, view);
	}

	private long second(int index) {
		return elements.get(index * 3) << 32L | elements.get(index * 3 + 1);
	}

	private int nano(int index) {
		return elements.get(index * 3 + 2);
	}

	private void put(int index, long seconds, int nanos) {
		elements.put(index * 3 + 0, (int) (seconds >> 32));
		elements.put(index * 3 + 1, (int) seconds);
		elements.put(index * 3 + 2, nanos);
	}

	@Override
	Instant getNoOffset(int index) {
		return Instant.ofEpochSecond(second(index), nano(index));
	}

	int search(Instant value) {
		return AbstractColumnSearch.binarySearch(this, offset, offset + size, value);
	}

	@Override
	int search(Instant value, boolean first) {
		return AbstractColumnSearch.search(this, value, first);
	}

	@Override
	void sort() {
		heapSort(offset, offset + size);
	}

	@Override
	int deduplicate() {
		return deduplicate(offset, offset + size);
	}

	@Override
	boolean checkSorted() {
		if (size < 2)
			return true;

		for (int i = offset + 1; i <= lastIndex(); i++) {
			if (compareValuesAt(i - 1, i) > 0)
				return false;
		}

		return true;
	}

	@Override
	boolean checkDistinct() {
		if (size < 2)
			return true;

		for (int i = offset + 1; i <= lastIndex(); i++) {
			if (compareValuesAt(i - 1, i) >= 0)
				return false;
		}

		return true;
	}

	@Override
	NonNullInstantColumn empty() {
		return EMPTY.get(characteristics);
	}

	@Override
	public ColumnType<Instant> getType() {
		return ColumnType.INSTANT;
	}

	@Override
	public int hashCode(int fromIndex, int toIndex) {
		// from Arrays::hashCode
		int result = 1;
		for (int i = fromIndex; i <= toIndex; i++) {

			long second = second(i);
			int nano = nano(i);
			long hilo = second ^ nano;

			result = 31 * result + ((int) (hilo >> 32)) ^ (int) hilo;
		}
		return result;
	}

	private void put(BigByteBuffer buffer, int index) {
		buffer.putLong(second(index));
		buffer.putInt(nano(index));
	}

	@Override
	NonNullInstantColumn applyFilter0(BufferBitSet keep, int cardinality) {

		BigByteBuffer buffer = allocate(cardinality);
		for (int i = offset; i <= lastIndex(); i++)
			if (keep.get(i - offset))
				put(buffer, i);
		buffer.flip();

		return new NonNullInstantColumn(buffer, 0, cardinality, characteristics, false);
	}

	@Override
	NonNullInstantColumn select0(IntColumn indices) {

		BigByteBuffer buffer = allocate(indices.size());
		for (int i = 0; i < indices.size(); i++)
			put(buffer, indices.getInt(i) + offset);
		buffer.flip();

		return construct(buffer, 0, indices.size(), NONNULL, false);
	}

	@Override
	int compareValuesAt(NonNullInstantColumn rhs, int l, int r) {

		return (this.second(l) < rhs.second(r) ? -1
				: (this.second(l) > rhs.second(r) ? 1
						: (this.nano(l) < rhs.nano(r) ? -1 : (this.nano(l) > rhs.nano(r) ? 1 : 0))));
	}

	private int compareValuesAt(int l, int r) {
		return compareValuesAt(this, l, r);
	}

	@Override
	void intersectLeftSorted(NonNullInstantColumn rhs, IntColumnBuilder indices, BufferBitSet keepRight) {

		for (int i = rhs.offset; i <= rhs.lastIndex(); i++) {

			int leftIndex = search(rhs.getNoOffset(i));
			if (leftIndex >= offset && leftIndex <= lastIndex()) {

				indices.add(leftIndex - offset);
				keepRight.set(i - rhs.offset);
			}
		}
	}

	@Override
	boolean checkType(Object o) {
		return o instanceof Instant;
	}

	@Override
	int elementSize() {
		return 12;
	}

	// =========================================================================

	private void heapSort(int fromIndex, int toIndex) {

		int n = toIndex - fromIndex;
		if (n <= 1)
			return;

		// Build max heap
		for (int i = fromIndex + n / 2 - 1; i >= fromIndex; i--)
			heapify(toIndex, i, fromIndex);

		// Heap sort
		for (int i = toIndex - 1; i >= fromIndex; i--) {
			swap(fromIndex, i);

			// Heapify root element
			heapify(i, fromIndex, fromIndex);
		}
	}

	// based on https://www.programiz.com/dsa/heap-sort
	private void heapify(int n, int i, int offset) {
		// Find largest among root, left child and right child
		int largest = i;
		int l = 2 * i + 1 - offset;
		int r = l + 1;

		if (l < n && compareValuesAt(l, largest) > 0)
			largest = l;

		if (r < n && compareValuesAt(r, largest) > 0)
			largest = r;

		// Swap and continue heapifying if root is not largest
		if (largest != i) {
			swap(i, largest);
			heapify(n, largest, offset);
		}
	}

	private void swap(int i, int j) {
		long is = second(i);
		int in = nano(i);
		long js = second(j);
		int jn = nano(j);

		put(i, js, jn);
		put(j, is, in);
	}

	// =========================================================================

	private int deduplicate(int fromIndex, int toIndex) {

		if (toIndex - fromIndex < 2)
			return toIndex;

		long prevS = second(fromIndex);
		int prevN = nano(fromIndex);
		int highest = fromIndex + 1;

		for (int i = fromIndex + 1; i < toIndex; i++) {
			long second = second(i);
			int nano = nano(i);

			if (prevS != second || prevN != nano) {
				if (highest < i)
					put(highest, second, nano);

				highest++;
				prevS = second;
				prevN = nano;
			}
		}

		return highest;
	}
}
