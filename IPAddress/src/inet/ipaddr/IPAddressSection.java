/*
 * Copyright 2017 Sean C Foley
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     or at
 *     https://github.com/seancfoley/IPAddress/blob/master/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package inet.ipaddr;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

import inet.ipaddr.Address.SegmentValueProvider;
import inet.ipaddr.AddressNetwork.PrefixConfiguration;
import inet.ipaddr.IPAddress.IPVersion;
import inet.ipaddr.IPAddressSection.WildcardOptions.WildcardOption;
import inet.ipaddr.format.AddressDivisionGrouping;
import inet.ipaddr.format.AddressDivisionGrouping.StringOptions.Wildcards;
import inet.ipaddr.format.IPAddressBitsDivision;
import inet.ipaddr.format.IPAddressDivisionGrouping;
import inet.ipaddr.format.IPAddressStringDivisionSeries;
import inet.ipaddr.format.util.IPAddressPartConfiguredString;
import inet.ipaddr.format.util.IPAddressPartStringCollection;
import inet.ipaddr.format.util.sql.IPAddressSQLTranslator;
import inet.ipaddr.format.util.sql.MySQLTranslator;
import inet.ipaddr.format.util.sql.SQLStringMatcher;
import inet.ipaddr.ipv6.IPv6Address;
import inet.ipaddr.ipv6.IPv6AddressSegment;

/**
 * A section of an IPAddress. 
 * 
 * It is a series of individual address segments.
 * <p>
 * IPAddressSection objects are immutable.  Some of the derived state is created upon demand and cached.
 * 
 * This also makes them thread-safe.
 * 
 * Almost all operations that can be performed on IPAddress objects can also be performed on IPAddressSection objects and vice-versa.
 * 
 */
public abstract class IPAddressSection extends IPAddressDivisionGrouping implements IPAddressSegmentSeries, AddressSection {
	
	private static final long serialVersionUID = 4L;
	private static final IPAddressStringDivisionSeries EMPTY_PARTS[];
	static {
		EMPTY_PARTS = new IPAddressStringDivisionSeries[0];
	}
	
	/* caches objects to avoid recomputing them */
	protected static class PrefixCache {
		/* for caching */
		private Integer networkMaskPrefixLen; //null indicates this field not initialized, -1 indicates the prefix len is null
		private Integer hostMaskPrefixLen; //null indicates this field not initialized, -1 indicates the prefix len is null
		
		/* also for caching */
		private Integer cachedMinPrefix; //null indicates this field not initialized
		private Integer cachedEquivalentPrefix; //null indicates this field not initialized, -1 indicates the prefix len is null
	}
	
	private transient PrefixCache prefixCache;
	private transient BigInteger cachedIPAddressCount;
	
	protected IPAddressSection(IPAddressSegment segments[], boolean cloneSegments, boolean checkSegs) {
		super(cloneSegments ? segments.clone() : segments, checkSegs);
		if(checkSegs) {//the segment array is populated, so we need to check the prefixes within to get the prefix length
			//we also must check the network does not change across segments
			IPAddressNetwork<?, ?, ?, ?, ?> network = getNetwork();
			Integer previousSegmentPrefix = null;
			for(int i = 0; i < segments.length; i++) {
				IPAddressSegment segment = segments[i];
				if(!network.equals(segment.getNetwork())) {
					throw new NetworkMismatchException(segment);
				}
				/**
				 * Across an address prefixes are:
				 * IPv6: (null):...:(null):(1 to 16):(0):...:(0)
				 * or IPv4: ...(null).(1 to 8).(0)...
				 */
				Integer segPrefix = segment.getSegmentPrefixLength();
				if(previousSegmentPrefix == null) {
					if(segPrefix != null) {
						cachedPrefixLength = getNetworkPrefixLength(i, segPrefix);
					}
				} else if(segPrefix == null || segPrefix != 0) {
					throw new InconsistentPrefixException(segments[i - 1], segment, segPrefix);
				}
				previousSegmentPrefix = segPrefix;
			}
			if(previousSegmentPrefix == null) {
				cachedPrefixLength = NO_PREFIX_LENGTH;
			}
		}
	}
	
	@Override
	protected Integer calculatePrefix() {
		int count = getSegmentCount();
		for(int i = 0; i < count; i++) { 
			IPAddressSegment div = getSegment(i);
			Integer prefix = div.getSegmentPrefixLength();
			if(prefix != null) {
				return getNetworkPrefixLength(i, prefix);
			}
		}
		return null;
	}
	
	protected abstract int getNetworkPrefixLength(int segmentIndex, int segmentPrefix);

	protected void checkSegments(IPv6AddressSegment segs[]) {
		IPAddressNetwork<?, ?, ?, ?, ?> network = getNetwork();
		for(IPAddressSegment seg : segs) {
			if(!network.equals(seg.getNetwork())) {
				throw new NetworkMismatchException(seg);
			}
		}
	}

	protected static String getMessage(String key) {
		return HostIdentifierException.getMessage(key);
	}
	
	protected void initCachedValues(
			Integer prefixLen,
			boolean network,
			Integer cachedNetworkPrefix,
			Integer cachedMinPrefix,
			Integer cachedEquivalentPrefix,
			BigInteger cachedCount,
			RangeList zeroSegments,
			RangeList zeroRanges) {
		if(prefixCache == null) {
			prefixCache = new PrefixCache();
		}
		if(network) {
			setNetworkMaskPrefix(prefixLen);
		} else {
			setHostMaskPrefix(prefixLen);
		}
		super.initCachedValues(cachedNetworkPrefix, cachedCount);
		prefixCache.cachedMinPrefix = cachedMinPrefix;
		prefixCache.cachedEquivalentPrefix = cachedEquivalentPrefix;
	}
	
	protected static RangeList getNoZerosRange() {
		return IPAddressDivisionGrouping.getNoZerosRange();
	}
	
	protected static RangeList getSingleRange(int index, int len) {
		return IPAddressDivisionGrouping.getSingleRange(index, len);
	}

	@Override
	public int getBitCount() {
		return getSegmentCount() * getBitsPerSegment();
	}
	
	@Override
	public int getByteCount() {
		return getSegmentCount() * getBytesPerSegment();
	}
	
	public static int bitsPerSegment(IPVersion version) {
		return IPAddressSegment.getBitCount(version);
	}
	
	public static int bytesPerSegment(IPVersion version) {
		return IPAddressSegment.getBitCount(version);
	}

	@Override
	public BigInteger getNonZeroHostCount() {
		if(isPrefixed() && getNetworkPrefixLength() < getBitCount()) {
			BigInteger cached = cachedIPAddressCount;
			if(cached == null) {
				cachedIPAddressCount = cached = getCountImpl(true);
			}
			return cached;
		}
		return getCount();
	}

	protected abstract BigInteger getCountImpl(boolean excludeZeroHosts);

	@Override
	public BigInteger getCountImpl() {
		return getCountImpl(false);
	}

	public boolean isIPv4() {
		return false;
	}
	
	public boolean isIPv6() {
		return false;
	}

	protected static boolean isPrefixSubnet(
			SegmentValueProvider lowerValueProvider,
			SegmentValueProvider upperValueProvider,
			int segmentCount,
			int bytesPerSegment,
			int bitsPerSegment,
			int segmentMaxValue,
			Integer networkPrefixLength,
			PrefixConfiguration prefixConfiguration,
			boolean fullRangeOnly) {
		if(networkPrefixLength == null || prefixConfiguration.prefixedSubnetsAreExplicit()) {
			return false;
		}
		if(networkPrefixLength < 0) {
			networkPrefixLength = 0;
		} else {
			int totalBitCount = (bitsPerSegment == 8) ? segmentCount << 3 : ((bitsPerSegment == 16) ? segmentCount << 4 : segmentCount * bitsPerSegment);
			if(networkPrefixLength >= totalBitCount) {
				return false;
			}
		}
		if(prefixConfiguration.allPrefixedAddressesAreSubnets()) {
			return true;
		}
		int prefixedSegment = getHostSegmentIndex(networkPrefixLength, bytesPerSegment, bitsPerSegment);
		int i = prefixedSegment;
		if(i < segmentCount) {
			int segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
			do {
				//we want to see if there is a sequence of zeros followed by a sequence of full-range bits from the prefix onwards
				//once we start seeing full range bits, the remained of the section must be full range
				//for instance:
				//segment 1 segment 2
				//upper: 10101010  10100111 11111111 11111111
				//lower: 00111010  00100000 00000000 00000000
				//                    x y
				//upper: 10101010  10100000 00000000 00111111
				//lower: 00111010  00100000 10000000 00000000
				//                           x         y
				//
				//the bit marked x in each set of 4 segment of 8 bits is a sequence of zeros, followed by full range bits starting at bit y
				int lower = lowerValueProvider.getValue(i);
				if(segmentPrefixLength == 0) {
					if(lower != 0) {
						return false;
					}
					int upper = upperValueProvider.getValue(i);
					if(fullRangeOnly) {
						if(upper != segmentMaxValue) {
							return false;
						}
					} else {
						int upperOnes = Integer.numberOfTrailingZeros(~upper);
						if(upperOnes > 0) {
							if((upper >>> upperOnes) != 0) {
								return false;
							}
							fullRangeOnly = true;
						} else if(upper != 0) {
							return false;
						}
					}
				} else {
					int segHostBits = bitsPerSegment - segmentPrefixLength;
					if(fullRangeOnly) {
						int hostMask = ~(~0 << segHostBits);
						if((hostMask & lower) != 0) {
							return false;
						}
						int upper = upperValueProvider.getValue(i);
						if((hostMask & upper) != hostMask) {
							return false;
						}
					} else {
						int lowerZeros = Integer.numberOfTrailingZeros(lower);
						if(lowerZeros < segHostBits) {
							return false;
						}
						int upper = upperValueProvider.getValue(i);
						int upperOnes = Integer.numberOfTrailingZeros(~upper);
						int upperZeros = Integer.numberOfTrailingZeros((upper | (~0 << bitsPerSegment)) >>> upperOnes);
						if(upperOnes + upperZeros < segHostBits) {
							return false;
						}
						if(upperOnes > 0) {
							fullRangeOnly = true;
						}
					}
				}
				segmentPrefixLength = 0;
			} while(++i < segmentCount);
		}
		return true;
	}

	/*
	 * Starting from the first host bit according to the prefix, if the section is a sequence of zeros in both low and high values, 
	 * followed by a sequence where low values are zero and high values are 1, then the section is a subnet prefix.
	 * 
	 * Note that this includes sections where hosts are all zeros, or sections where hosts are full range of values, 
	 * so the sequence of zeros can be empty and the sequence of where low values are zero and high values are 1 can be empty as well.
	 * However, if they are both empty, then this returns false, there must be at least one bit in the sequence.
	 */
	protected static boolean isPrefixSubnet(IPAddressSegment sectionSegments[], Integer networkPrefixLength, IPAddressNetwork<?, ?, ?, ?, ?> network, boolean fullRangeOnly) {
		int segmentCount = sectionSegments.length;
		if(segmentCount == 0) {
			return false;
		}
		IPAddressSegment seg = sectionSegments[0];
		return isPrefixSubnet(
				(segmentIndex) -> sectionSegments[segmentIndex].getLowerSegmentValue(),
				(segmentIndex) -> sectionSegments[segmentIndex].getUpperSegmentValue(),
				segmentCount,
				seg.getByteCount(),
				seg.getBitCount(),
				seg.getMaxSegmentValue(),
				networkPrefixLength,
				network.getPrefixConfiguration(),
				fullRangeOnly);
	}
	
	//this method is basically checking whether we can return "this" for getNetworkSection
	protected boolean isNetworkSection(int networkPrefixLength, boolean withPrefixLength) {
		int segmentCount = getSegmentCount();
		if(segmentCount == 0) {
			return true;
		}
		int bitsPerSegment = getBitsPerSegment();
		int prefixedSegmentIndex = getNetworkSegmentIndex(networkPrefixLength, getBytesPerSegment(), bitsPerSegment);
		if(prefixedSegmentIndex + 1 < segmentCount) {
			return false; //not the right number of segments
		}
		//the segment count matches, now compare the prefixed segment
		int segPrefLength = getPrefixedSegmentPrefixLength(bitsPerSegment, networkPrefixLength, prefixedSegmentIndex);
		return !getSegment(segmentCount - 1).isNetworkChangedByPrefix(segPrefLength, withPrefixLength);
	}
	
	protected boolean isHostSection(int networkPrefixLength) {
		int segmentCount = getSegmentCount();
		if(segmentCount == 0) {
			return true;
		}
		if(networkPrefixLength >= getBitsPerSegment()) {
			return false;
		}
		return !getSegment(0).isHostChangedByPrefix(networkPrefixLength);
	}
	
	protected static int getNetworkSegmentIndex(int networkPrefixLength, int bytesPerSegment, int bitsPerSegment) {
		return AddressDivisionGrouping.getNetworkSegmentIndex(networkPrefixLength, bytesPerSegment, bitsPerSegment);
	}
	
	protected static int getHostSegmentIndex(int networkPrefixLength, int bytesPerSegment, int bitsPerSegment) {
		return AddressDivisionGrouping.getHostSegmentIndex(networkPrefixLength, bytesPerSegment, bitsPerSegment);
	}
	
	private Integer checkForPrefixMask(boolean network) {
		int front, back;
		if(network) {
			front = getSegment(0).getMaxSegmentValue();
			back = 0;
		} else {
			back = getSegment(0).getMaxSegmentValue();
			front = 0;
		}
		int prefixLen = 0;
		int count = getSegmentCount();
		for(int i=0; i < count; i++) {
			IPAddressSegment seg = getSegment(i);
			int value = seg.getLowerSegmentValue();
			if(value != front) {
				Integer segmentPrefixLen = seg.getBlockMaskPrefixLength(network);
				if(segmentPrefixLen == null) {
					return null;
				}
				prefixLen += segmentPrefixLen;
				for(i++; i < count; i++) {
					value = getSegment(i).getLowerSegmentValue();
					if(value != back) {
						return null;
					}
				}
			} else {
				prefixLen += seg.getBitCount();
			}
		}
		//note that when segments.length == 0, we return 0 as well, since both the host mask and prefix mask are empty (length of 0 bits)
		return prefixLen;
	}
	
	/**
	 * If this address section is equivalent to the mask for a CIDR prefix block, it returns that prefix length.
	 * Otherwise, it returns null.
	 * A CIDR network mask is an address with all 1s in the network section and then all 0s in the host section.
	 * A CIDR host mask is an address with all 0s in the network section and then all 1s in the host section.
	 * The prefix length is the length of the network section.
	 * 
	 * Also, keep in mind that the prefix length returned by this method is not equivalent to the prefix length used to construct this object.
	 * The prefix length used to construct indicates the network and host portion of this address.  
	 * The prefix length returned here indicates the whether the value of this address can be used as a mask for the network and host
	 * portion of any other address.  Therefore the two values can be different values, or one can be null while the other is not.
	 * 
	 * This method applies only to the lower value of the range if this section represents multiple values.
	 * 
	 * @param network whether to check for a network mask or a host mask
	 * @return the prefix length corresponding to this mask, or null if there is no such prefix length
	 */
	public Integer getBlockMaskPrefixLength(boolean network) {
		Integer prefixLen;
		if(network) {
			if(hasNoPrefixCache() || (prefixLen = prefixCache.networkMaskPrefixLen) == null) {
				prefixLen = setNetworkMaskPrefix(checkForPrefixMask(network));
			}
		} else {
			if(hasNoPrefixCache() || (prefixLen = prefixCache.hostMaskPrefixLen) == null) {
				prefixLen = setHostMaskPrefix(checkForPrefixMask(network));
			}
		}
		if(prefixLen < 0) {
			return null;
		}
		return prefixLen;
	}
	
	private Integer setHostMaskPrefix(Integer prefixLen) {
		if(prefixLen == null) {
			prefixLen = prefixCache.hostMaskPrefixLen = NO_PREFIX_LENGTH;
		} else {
			prefixCache.hostMaskPrefixLen = prefixLen;
			prefixCache.networkMaskPrefixLen = NO_PREFIX_LENGTH; //cannot be both network and host mask
		}
		return prefixLen;
	}
	
	private Integer setNetworkMaskPrefix(Integer prefixLen) {
		if(prefixLen == null) {
			prefixLen = prefixCache.networkMaskPrefixLen = NO_PREFIX_LENGTH;
		} else {
			prefixCache.networkMaskPrefixLen = prefixLen;
			prefixCache.hostMaskPrefixLen = NO_PREFIX_LENGTH; //cannot be both network and host mask
		}
		return prefixLen;
	}

	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment>
			R getNetworkSection(
					R original,
					int networkPrefixLength,
					boolean withPrefixLength,
					IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator creator,
					BiFunction<Integer, Integer, S> segProducer) {
		if(networkPrefixLength < 0 || networkPrefixLength > original.getBitCount()) {
			throw new PrefixLenException(original, networkPrefixLength);
		}
		if(original.isNetworkSection(networkPrefixLength, withPrefixLength)) {
			return original;
		}
		int bitsPerSegment = original.getBitsPerSegment();
		int networkSegmentCount = original.getNetworkSegmentCount(networkPrefixLength);
		S result[] = creator.createSegmentArray(networkSegmentCount);
		for(int i = 0; i < networkSegmentCount; i++) {
			Integer prefix = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
			result[i] = segProducer.apply(i, prefix);
		}
		return creator.createSectionInternal(result);
	}
	
	public Integer getNetworkSegmentCount() {
		if(isPrefixed()) {
			return getNetworkSegmentCount(getNetworkPrefixLength());
		}
		return null;
	}

	protected int getNetworkSegmentCount(int networkPrefixLength) {
		return getNetworkSegmentIndex(networkPrefixLength, getBytesPerSegment(), getBitsPerSegment()) + 1;
	}
	
	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment> 
			R getHostSection(R original, int networkPrefixLength, int hostSegmentCount, IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator creator,
					BiFunction<Integer, Integer, S> segProducer) {
		if(networkPrefixLength < 0 || networkPrefixLength > original.getBitCount()) {
			throw new PrefixLenException(original, networkPrefixLength);
		}
		if(original.isHostSection(networkPrefixLength)) {
			return original;
		}
		int segmentCount = original.getSegmentCount();
		S result[] = creator.createSegmentArray(hostSegmentCount);
		if(hostSegmentCount > 0) {
			int bitsPerSegment = original.getBitsPerSegment();
			for(int i = hostSegmentCount - 1, j = segmentCount - 1; i >= 0; i--, j--) {
				Integer prefix = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, j);
				result[i] = segProducer.apply(j, prefix);
			}
		}
		return creator.createSectionInternal(result);
	}
	
	protected int getHostSegmentCount(int networkPrefixLength) {
		return getSegmentCount() - getHostSegmentIndex(networkPrefixLength, getBytesPerSegment(), getBitsPerSegment());
	}

	public Integer getHostSegmentCount() {
		if(isPrefixed()) {
			return getHostSegmentCount(getNetworkPrefixLength());
		}
		return null;
	}
	
	public Integer getHostBitCount() {
		if(isPrefixed()) {
			return getHostBitCount(getNetworkPrefixLength());
		}
		return null;
	}
	
	private int getHostBitCount(int networkPrefixLength) {
		return getBitCount() - networkPrefixLength;
	}
	
	@FunctionalInterface
	public interface SegFunction<R, S> {
	    S apply(R section, int value);
	}
	
	protected static <R extends IPAddressSection, S extends IPAddressSegment> R setPrefixLength(
			R original,
			IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator,
			int networkPrefixLength,
			boolean withZeros,
			boolean noShrink,
			SegFunction<R, S> segProducer) {
		Integer existingPrefixLength = original.getNetworkPrefixLength();
		if(existingPrefixLength != null) {
			if(networkPrefixLength == existingPrefixLength) {
				return original;
			} else if(noShrink && networkPrefixLength > existingPrefixLength) {
				return original;
			}
		}
		original.checkSubnet(networkPrefixLength);
		IPAddressNetwork<?, R, ?, S, ?> network = creator.getNetwork();
		int maskBits;	
		if(network.getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
			if(existingPrefixLength != null) {
				if(networkPrefixLength > existingPrefixLength) {
					if(withZeros) {
						maskBits = existingPrefixLength;
					} else {
						maskBits = networkPrefixLength;
					}
				} else if(networkPrefixLength < existingPrefixLength) {
					maskBits = networkPrefixLength;
				} else {
					return original;
				}
			} else {
				maskBits = networkPrefixLength;
			}
		} else {
			if(existingPrefixLength != null) {
				if(networkPrefixLength == existingPrefixLength) {
					return original;
				}
				if(withZeros) {
					R leftMask, rightMask;
					if(networkPrefixLength > existingPrefixLength) {
						leftMask = network.getNetworkMaskSection(existingPrefixLength);
						rightMask = network.getHostMaskSection(networkPrefixLength);
					} else {
						leftMask = network.getNetworkMaskSection(networkPrefixLength);
						rightMask = network.getHostMaskSection(existingPrefixLength);
					}
					return getSubnetSegments(original, networkPrefixLength, creator, false,
							i -> segProducer.apply(original, i), 
							i -> {
								int val1 = segProducer.apply(leftMask, i).getLowerSegmentValue();
								int val2 = segProducer.apply(rightMask, i).getLowerSegmentValue();
								return val1 | val2;
							},
							false
					);
				}
			}
			maskBits = original.getBitCount();
		}
		R mask = network.getNetworkMaskSection(maskBits);
		return getSubnetSegments(original, networkPrefixLength, creator, false, i -> segProducer.apply(original, i), i -> segProducer.apply(mask, i).getLowerSegmentValue(), false);
	}
	
	protected static <R extends IPAddressSection, S extends IPAddressSegment> R getSubnetSegments(
			R original,
			Integer networkPrefixLength,
			IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator,
			boolean verifyMask,
			IntFunction<S> segProducer,
			IntUnaryOperator segmentMaskProducer,
			boolean singleOnly) {
		if(networkPrefixLength != null && (networkPrefixLength < 0 || networkPrefixLength > original.getBitCount())) {
			throw new PrefixLenException(original, networkPrefixLength);
		}
		int bitsPerSegment = original.getBitsPerSegment();
		int count = original.getSegmentCount();
		for(int i = 0; i < count; i++) {
			Integer segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
			S seg = segProducer.apply(i);
			int maskValue = segmentMaskProducer.applyAsInt(i);
			if(seg.isChangedByMask(maskValue, segmentPrefixLength)) {
				if(verifyMask && !seg.isMaskCompatibleWithRange(maskValue, segmentPrefixLength)) {
					throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
				}
				S newSegments[] = creator.createSegmentArray(original.getSegmentCount());
				original.getSegments(0, i, newSegments, 0);
				newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() & maskValue, seg.getUpperSegmentValue() & maskValue, segmentPrefixLength);		
				if(original.getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
					if(segmentPrefixLength == null) {
						for(i++; i < count; i++) {
							segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
							seg  = segProducer.apply(i);
							maskValue = segmentMaskProducer.applyAsInt(i);
							if(!seg.isChangedByMask(maskValue, segmentPrefixLength)) {
								newSegments[i] = seg;
							} else {
								if(verifyMask && !seg.isMaskCompatibleWithRange(maskValue, segmentPrefixLength)) {
									throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
								}
								newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() & maskValue, seg.getUpperSegmentValue() & maskValue, segmentPrefixLength);
							}
							if(segmentPrefixLength != null) {
								break;
							}
						}
					}
					if(++i < count) {
						S zeroSeg = creator.createSegment(0, 0);
						Arrays.fill(newSegments, i, count, zeroSeg);
					}
				} else {
					for(i++; i < count; i++) {
						segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
						seg  = segProducer.apply(i);
						maskValue = segmentMaskProducer.applyAsInt(i);
						if(seg.isChangedByMask(maskValue, segmentPrefixLength)) {
							if(verifyMask && !seg.isMaskCompatibleWithRange(maskValue, segmentPrefixLength)) {
								throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
							}
							newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() & maskValue, seg.getUpperSegmentValue() & maskValue, segmentPrefixLength);
						} else {
							newSegments[i] = seg;
						}
					}
				}
				return creator.createPrefixedSectionInternal(newSegments, networkPrefixLength, singleOnly);
			}
		}
		return original;
	}
	
	protected static <R extends IPAddressSection, S extends IPAddressSegment> R getOredSegments(
			R original,
			Integer networkPrefixLength,
			IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator,
			IntFunction<S> segProducer,
			IntUnaryOperator segmentMaskProducer) {
		if(networkPrefixLength != null && (networkPrefixLength < 0 || networkPrefixLength > original.getBitCount())) {
			throw new PrefixLenException(original, networkPrefixLength);
		}
		int bitsPerSegment = original.getBitsPerSegment();
		int count = original.getSegmentCount();
		for(int i = 0; i < count; i++) {
			Integer segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
			S seg = segProducer.apply(i);
			int maskValue = segmentMaskProducer.applyAsInt(i);
			if(seg.isChangedByOr(maskValue, segmentPrefixLength)) {
				if(!seg.isBitwiseOrCompatibleWithRange(maskValue, segmentPrefixLength)) {
					throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
				}
				S newSegments[] = creator.createSegmentArray(original.getSegmentCount());
				original.getSegments(0, i, newSegments, 0);
				newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() | maskValue, seg.getUpperSegmentValue() | maskValue, segmentPrefixLength);
				if(seg.getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
					if(segmentPrefixLength == null) {
						for(i++; i < count; i++) {
							segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
							seg  = segProducer.apply(i);
							maskValue = segmentMaskProducer.applyAsInt(i);
							if(!seg.isChangedByOr(maskValue, segmentPrefixLength)) {
								newSegments[i] = seg;
							} else {
								if(!seg.isBitwiseOrCompatibleWithRange(maskValue, segmentPrefixLength)) {
									throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
								}
								newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() | maskValue, seg.getUpperSegmentValue() | maskValue, segmentPrefixLength);
							}
							if(segmentPrefixLength != null) {
								break;
							}
						}
					}
					if(++i < count) {
						S zeroSeg = creator.createSegment(0, seg.getMaxSegmentValue(), 0);
						Arrays.fill(newSegments, i, count, zeroSeg);
					}
				} else {
					for(i++; i < count; i++) {
						segmentPrefixLength = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
						seg  = segProducer.apply(i);
						maskValue = segmentMaskProducer.applyAsInt(i);
						if(!seg.isChangedByOr(maskValue, segmentPrefixLength)) {
							newSegments[i] = seg;
						} else {
							if(!seg.isBitwiseOrCompatibleWithRange(maskValue, segmentPrefixLength)) {
								throw new IncompatibleAddressException(seg, "ipaddress.error.maskMismatch");
							}
							newSegments[i] = creator.createSegment(seg.getLowerSegmentValue() | maskValue, seg.getUpperSegmentValue() | maskValue, segmentPrefixLength);
						}
					}
				}
				return creator.createPrefixedSectionInternal(newSegments, networkPrefixLength);
			}
		}
		return original;
	}
	
	//call this instead of the method below if you already know the networkPrefixLength doesn't extend to the end of the last segment of the section or address
	static Integer getSplitSegmentPrefixLength(int bitsPerSegment, Integer networkPrefixLength, int segmentIndex) {
		if(networkPrefixLength != null) {
			return getPrefixedSegmentPrefixLength(bitsPerSegment, networkPrefixLength, segmentIndex);
		}
		return null;
	}

	/**
	 * Across the address prefixes are:
	 * IPv6: (null):...:(null):(1 to 16):(0):...:(0)
	 * or IPv4: ...(null).(1 to 8).(0)...
	 */
	public static Integer getSegmentPrefixLength(int bitsPerSegment, Integer prefixLength, int segmentIndex) {
		return AddressDivisionGrouping.getSegmentPrefixLength(bitsPerSegment, prefixLength, segmentIndex);
	}
	
	/**
	 * Across the address prefixes are:
	 * IPv6: (null):...:(null):(1 to 16):(0):...:(0)
	 * or IPv4: ...(null).(1 to 8).(0)...
	 */
	protected static Integer getSegmentPrefixLength(int bitsPerSegment, int segmentPrefixedBits) {
		return AddressDivisionGrouping.getSegmentPrefixLength(bitsPerSegment, segmentPrefixedBits);
	}

	protected static <R extends IPAddressSection, S extends IPAddressSegment> R getLowestOrHighestSection(
			R section,
			IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator,
			Supplier<Iterator<S[]>> nonZeroHostIteratorSupplier,
			IntFunction<S> segProducer,
			boolean lowest,
			boolean excludeZeroHost) {
		boolean create = true;
		R result = null;
		S[] segs = null;
		if(lowest && excludeZeroHost && section.includesZeroHost()) {
			Iterator<S[]> it = nonZeroHostIteratorSupplier.get();
			if(!it.hasNext()) {
				create = false;
			} else {
				segs = it.next();
			}
		} else {
			segs = createSingle(section, creator, segProducer);
		}
		if(create) {
			Integer prefLength;
			result = section.getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets() || (prefLength = section.getNetworkPrefixLength()) == null ? 
				creator.createSectionInternal(segs) :
				creator.createPrefixedSectionInternal(segs, prefLength, true);
		}
		return result;
	}
	
	@Override
	public int getSegmentCount() {
		return getDivisionCount();
	}
	
	@Override
	public IPAddressSegment getSegment(int index) {
		return getSegmentsInternal()[index];
	}

	/**
	 * @param other
	 * @return whether this subnet contains the given address section
	 */
	public boolean contains(IPAddressSection other) {
		//check if they are comparable first
		if(getSegmentCount() != other.getSegmentCount()) {
			return false;
		}
		boolean prefixIsSubnet = isPrefixed() && getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets();
		for(int i=0; i < getSegmentCount(); i++) {
			IPAddressSegment seg = getSegment(i);
			if(!seg.contains(other.getSegment(i))) {
				return false;
			}
			if(prefixIsSubnet && seg.isPrefixed()) {
				//no need to check host section which contains all possible hosts
				break;
			}
		}
		return true;
	}
	

	@Override
	public boolean isFullRange() {
		int divCount = getDivisionCount();
		boolean allPrefixedAddressesAreSubnets = getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets();
		if(allPrefixedAddressesAreSubnets) {
			for(int i = 0; i < divCount; i++) {
				IPAddressSegment div = getSegment(i);
				if(!div.isFullRange()) {
					return false;
				}
				Integer prefix = div.getSegmentPrefixLength();
				if(prefix != null) {
					//any segment that follows is full range
					break;
				}
			}
		} else for(int i = 0; i < divCount; i++) {
			IPAddressSegment div = getSegment(i);
			if(!div.isFullRange()) {
				return false;
			}
		}
		return true;
	}
	
	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment> R 
			intersect(R first, R other, IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator addrCreator, IntFunction<S> segProducer, IntFunction<S> otherSegProducer) {
		//check if they are comparable first.  We only check segment count, we do not care about start index.
		first.checkSectionCount(other);
		
		//larger prefix length should prevail?    hmmmmm... I would say that is true, choose the larger prefix
		Integer pref = first.getNetworkPrefixLength();
		Integer otherPref = other.getNetworkPrefixLength();
		if(pref != null) {
			if(otherPref != null) {
				pref = Math.max(pref, otherPref);
			} else {
				pref = null;
			}
		}
				
		if(other.contains(first)) {
			if(Objects.equals(pref, first.getNetworkPrefixLength())) {
				return first;
			}
		} else if(!first.isMultiple()) {
			return null;
		}
		if(first.contains(other)) {
			if(Objects.equals(pref, other.getNetworkPrefixLength())) {
				return other;
			}
		} else if(!other.isMultiple()) {
			return null;
		}
		
		int segCount = first.getSegmentCount();
		for(int i = 0; i < segCount; i++) {
			IPAddressSegment seg = first.getSegment(i);
			IPAddressSegment otherSeg = other.getSegment(i);
			int lower = seg.getLowerSegmentValue();
			int higher = seg.getUpperSegmentValue();
			int otherLower = otherSeg.getLowerSegmentValue();
			int otherHigher = otherSeg.getUpperSegmentValue();
			if(otherLower > higher || lower > otherHigher) {
				//no overlap in this segment means no overlap at all
				return null;
			}
		}
		
		S segs[] = addrCreator.createSegmentArray(segCount);
		for(int i = 0; i < segCount; i++) {
			S seg = segProducer.apply(i);
			S otherSeg = otherSegProducer.apply(i);
			Integer segPref = getSegmentPrefixLength(seg.getBitCount(), pref, i);
			if(seg.contains(otherSeg)) {
				if(!otherSeg.isChangedByPrefix(segPref, false)) {
					segs[i] = otherSeg;
					continue;
				}
			}
			if(otherSeg.contains(seg)) {
				if(!seg.isChangedByPrefix(segPref, false)) {
					segs[i] = seg;
					continue;
				}
			}
			int lower = seg.getLowerSegmentValue();
			int higher = seg.getUpperSegmentValue();
			int otherLower = otherSeg.getLowerSegmentValue();
			int otherHigher = otherSeg.getUpperSegmentValue();
			int newLower = Math.max(lower, otherLower);
			int newHigher = Math.min(higher, otherHigher);
			segs[i] = addrCreator.createSegment(newLower, newHigher, segPref);
		}
		R result = addrCreator.createSection(segs);
		return result;
	}
	
	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment> R[] 
			subtract(R first, R other, IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator addrCreator, IntFunction<S> segProducer, BiFunction<R, Integer, R> prefixApplier) {
		//check if they are comparable first
		first.checkSectionCount(other);
		if(!first.isMultiple()) {
			if(other.contains(first)) {
				return null;
			}
			R result[] = addrCreator.createSectionArray(1);
			result[0] = first;
			return result;
		}
		//getDifference: same as removing the intersection
		//   first you confirm there is an intersection in each segment.  
		// Then you remove each intersection, one at a time, leaving the other segments the same, since only one segment needs to differ.
		// To prevent adding the same section twice, use only the intersection (ie the relative complement of the diff) of segments already handled and not the whole segment.
		
		// For example: 0-3.0-3.2.4 subtracting 1-4.1-3.2.4
		// The diff of the first segment is just 0, giving 0.0-3.2.4
		// The diff of the second segment is also 0, but for the first segment we use the intersection since we handled the first already, giving 1-3.0.2.4
		int segCount = first.getSegmentCount();
		for(int i = 0; i < segCount; i++) {
			IPAddressSegment seg = first.getSegment(i);
			IPAddressSegment otherSeg = other.getSegment(i);
			int lower = seg.getLowerSegmentValue();
			int higher = seg.getUpperSegmentValue();
			int otherLower = otherSeg.getLowerSegmentValue();
			int otherHigher = otherSeg.getUpperSegmentValue();
			if(otherLower > higher || lower > otherHigher) {
				//no overlap in this segment means no overlap at all
				R result[] = addrCreator.createSectionArray(1);
				result[0] = first;
				return result;
			}
		}
		
		S intersections[] = addrCreator.createSegmentArray(segCount);
		ArrayList<R> sections = new ArrayList<R>();
		for(int i = 0; i < segCount; i++) {
			S seg = segProducer.apply(i);
			IPAddressSegment otherSeg = other.getSegment(i);
			int lower = seg.getLowerSegmentValue();
			int higher = seg.getUpperSegmentValue();
			int otherLower = otherSeg.getLowerSegmentValue();
			int otherHigher = otherSeg.getUpperSegmentValue();
			if(lower >= otherLower) {
				if(higher <= otherHigher) {
					//this segment is contained in the other
					if(seg.isPrefixed()) {
						intersections[i] = addrCreator.createSegment(lower, higher, null);
					} else {
						intersections[i] = seg;
					}
					continue;
				}
				//otherLower <= lower <= otherHigher < higher
				intersections[i] = addrCreator.createSegment(lower, otherHigher, null);
				R section = createDiffSection(first, otherHigher + 1, higher, i, addrCreator, segProducer, intersections);
				sections.add(section);
			} else {
				//lower < otherLower <= otherHigher
				R section = createDiffSection(first, lower, otherLower - 1, i, addrCreator, segProducer, intersections);
				sections.add(section);
				if(higher <= otherHigher) {
					intersections[i] = addrCreator.createSegment(otherLower, higher, null);
				} else {
					//lower < otherLower <= otherHigher < higher
					intersections[i] = addrCreator.createSegment(otherLower, otherHigher, null);
					section = createDiffSection(first, otherHigher + 1, higher, i, addrCreator, segProducer, intersections);
					sections.add(section);
				}
			}
		}
		
		//apply the prefix to the sections
		//for each section, we figure out what each prefix length should be
		if(first.isPrefixed()) {
			int thisPrefix = first.getNetworkPrefixLength();
			for(int i = 0; i < sections.size(); i++) {
				R section = sections.get(i);
				int bitCount = section.getBitCount();
				int totalPrefix = bitCount;
				for(int j = first.getSegmentCount() - 1; j >= 0 ; j--) {
					IPAddressSegment seg = section.getSegment(j);
					int segBitCount = seg.getBitCount();
					int segPrefix = seg.getMinPrefixLengthForBlock();
					if(segPrefix == segBitCount) {
						break;
					} else {
						totalPrefix -= segBitCount;
						if(segPrefix != 0) {
							totalPrefix += segPrefix;
							break;
						}
					}
				}
				if(totalPrefix != bitCount) {
					if(totalPrefix < thisPrefix) {
						totalPrefix = thisPrefix;
					}
					section = prefixApplier.apply(section, totalPrefix);
					sections.set(i, section);
				}
			}
		}
		if(sections.size() == 0) {
			return null;
		}
		R result[] = addrCreator.createSectionArray(sections.size());
		sections.toArray(result);
		return result;
	}
	
	private static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment> R 
			createDiffSection(R original, int lower, int upper, int diffIndex, IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator addrCreator, IntFunction<S> segProducer, S intersectingValues[]) {
		int segCount = original.getSegmentCount();
		S segments[] = addrCreator.createSegmentArray(segCount);
		for(int j = 0; j < diffIndex; j++) {
			segments[j] = intersectingValues[j];
		}
		S diff = addrCreator.createSegment(lower, upper, null);
		segments[diffIndex] = diff;
		for(int j = diffIndex + 1; j < segCount; j++) {
			segments[j] = segProducer.apply(j);
		}
		R section = addrCreator.createSectionInternal(segments);
		return section;
	}

	@Override
	public abstract IPAddressSection applyPrefixLength(int networkPrefixLength) throws PrefixLenException;
	
	protected void checkSubnet(int networkPrefixLength) throws PrefixLenException {
		if(networkPrefixLength < 0 || networkPrefixLength > getBitCount()) {
			throw new PrefixLenException(this, networkPrefixLength);
		}
	}

	protected void checkSectionCount(IPAddressSection mask) throws SizeMismatchException {
		if(mask.getSegmentCount() != getSegmentCount()) {
			throw new SizeMismatchException(this, mask);
		}
	}
		
	@Override
	public abstract IPAddressSection toPrefixBlock();

	@Override
	public abstract IPAddressSection toPrefixBlock(int networkPrefixLength);
	
	/**
	 * Returns the equivalent CIDR address section with a prefix length for which the subnet block for that prefix matches the range of values in this section.
	 * <p>
	 * If no such prefix length exists, returns null.
	 * <p>
	 * If this address represents just a single address, "this" is returned.
	 * 
	 * @return
	 */
	@Override
	public IPAddressSection assignPrefixForSingleBlock() {
		if(!isMultiple()) {
			return this;
		}
		Integer newPrefix = getPrefixLengthForSingleBlock();
		return newPrefix == null ? null : setPrefixLength(newPrefix, false);
	}
	
	/**
	 * Constructs an equivalent address section with the smallest CIDR prefix possible (largest network),
	 * such that the range of values are a set of subnet blocks for that prefix.
	 * 
	 * @return
	 */
	@Override
	public IPAddressSection assignMinPrefixForBlock() {
		return setPrefixLength(getMinPrefixLengthForBlock(), false);
	}

	@Override
	public boolean includesZeroHost() {
		Integer networkPrefixLength = getNetworkPrefixLength();
		if(networkPrefixLength == null) {
			return isZero();
		}
		if(networkPrefixLength >= getBitCount()) {
			return false;
		}
		if(getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
			return true;
		}
		int prefixedSegmentIndex = getHostSegmentIndex(networkPrefixLength, getBytesPerSegment(), getBitsPerSegment());
		int divCount = getSegmentCount();
		for(int i = prefixedSegmentIndex; i < divCount; i++) {
			IPAddressSegment div = getSegment(i);
			Integer segmentPrefixLength = div.getDivisionPrefixLength();
			if(segmentPrefixLength != null) {
				int mask = div.getSegmentHostMask(segmentPrefixLength);
				if((mask & div.getLowerValue()) != 0) {
					return false;
				}
				for(++i; i < divCount; i++) {
					div = getSegment(i);
					if(!div.includesZero()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	public boolean includesMaxHost() {
		Integer networkPrefixLength = getNetworkPrefixLength();
		if(networkPrefixLength == null) {
			return isMax();
		}
		if(networkPrefixLength >= getBitCount()) {
			return false;
		}
		if(getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
			return true;
		}
		int prefixedSegmentIndex = getHostSegmentIndex(networkPrefixLength, getBytesPerSegment(), getBitsPerSegment());
		int divCount = getSegmentCount();
		for(int i = prefixedSegmentIndex; i < divCount; i++) {
			IPAddressSegment div = getSegment(i);
			Integer segmentPrefixLength = div.getDivisionPrefixLength();
			if(segmentPrefixLength != null) {
				int mask = div.getSegmentHostMask(segmentPrefixLength);
				if((mask & div.getUpperSegmentValue()) != mask) {
					return false;
				}
				for(++i; i < divCount; i++) {
					div = getSegment(i);
					if(!div.includesMax()) {
						return false;
					}
				}
			}
		}
		return true;
	}
	
	/**
	 * 
	 * @return whether the network portion of the address, the prefix, consists of a single value
	 */
	public boolean isSingleNetwork() {
		Integer networkPrefixLength = getNetworkPrefixLength();
		if(networkPrefixLength == null || networkPrefixLength >= getBitCount()) {
			return !isMultiple();
		}
		int prefixedSegmentIndex = getNetworkSegmentIndex(networkPrefixLength, getBytesPerSegment(), getBitsPerSegment());
		if(prefixedSegmentIndex < 0) {
			return true;
		}
		for(int i = 0; i < prefixedSegmentIndex; i++) {
			IPAddressSegment div = getSegment(i);
			if(div.isMultiple()) {
				return false;
			}
		}
		IPAddressSegment div = getSegment(prefixedSegmentIndex);
		int differing = div.getLowerSegmentValue() ^ div.getUpperSegmentValue();
		if(differing == 0) {
			return true;
		}
		int bitsPerSegment = div.getBitCount();
		int highestDifferingBitInRange = Integer.numberOfLeadingZeros(differing) - (Integer.SIZE - bitsPerSegment);
		return getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, prefixedSegmentIndex) <= highestDifferingBitInRange;
	}
	
	/**
	 * Applies the mask to this address section and then compares values with the given address section
	 * 
	 * @param mask
	 * @param other
	 * @return
	 */
	public boolean matchesWithMask(IPAddressSection other, IPAddressSection mask) {
		checkSectionCount(mask);
		checkSectionCount(other);
		int divCount = getSegmentCount();
		for(int i = 0; i < divCount; i++) {
			IPAddressSegment div = getSegment(i);
			IPAddressSegment maskSegment = mask.getSegment(i);
			IPAddressSegment otherSegment = other.getSegment(i);
			if(!div.matchesWithMask(
					otherSegment.getLowerSegmentValue(), 
					otherSegment.getUpperSegmentValue(), 
					maskSegment.getLowerSegmentValue())) {
				return false;
			}
		}
		return true;
	}
	
	@Override
	public abstract IPAddressSection removePrefixLength(boolean zeroed);

	@Override
	public abstract IPAddressSection removePrefixLength();
	
	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment>
			R toPrefixBlock(
					R original,
					int networkPrefixLength,
					IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator creator,
					BiFunction<Integer, Integer, S> segProducer) {
		if(networkPrefixLength < 0 || networkPrefixLength > original.getBitCount()) {
			throw new PrefixLenException(original, networkPrefixLength);
		}
		if(original.isNetworkSubnet(networkPrefixLength)) {
			return original;
		}
		int bitsPerSegment = original.getBitsPerSegment();
		int segmentCount = original.getSegmentCount();
		S result[] = creator.createSegmentArray(segmentCount);
		for(int i = 0; i < segmentCount; i++) {
			Integer prefix = getSegmentPrefixLength(bitsPerSegment, networkPrefixLength, i);
			result[i] = segProducer.apply(i, prefix);
		}
		return creator.createSectionInternal(result);
	}
	
	//this method is basically checking whether we can return "this" for getNetworkSection
	protected boolean isNetworkSubnet(int networkPrefixLength) {
		int segmentCount = getSegmentCount();
		if(segmentCount == 0) {
			return true;
		}
		int bitsPerSegment = getBitsPerSegment();
		int prefixedSegmentIndex = getHostSegmentIndex(networkPrefixLength, getBytesPerSegment(), bitsPerSegment);
		int segPrefLength = getPrefixedSegmentPrefixLength(bitsPerSegment, networkPrefixLength, prefixedSegmentIndex);
		if(getSegment(prefixedSegmentIndex).isNetworkChangedByPrefix(segPrefLength, true)) {
			return false;
		}
		if(!getNetwork().getPrefixConfiguration().allPrefixedAddressesAreSubnets()) {
			for(int i = prefixedSegmentIndex + 1; i < segmentCount; i++) {
				if(!getSegment(i).isFullRange()) {
					return false;
				}
			}
		}
		return true;
	}

	protected static <R extends IPAddressSection, S extends IPAddressSegment> R removePrefixLength(
			R original, boolean zeroed, IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator, SegFunction<R, S> segProducer) {
		if(!original.isPrefixed()) {
			return original;
		}
		IPAddressNetwork<?, R, ?, S, ?> network = creator.getNetwork();
		R mask = network.getNetworkMaskSection(zeroed ? original.getPrefixLength() : original.getBitCount());
		return getSubnetSegments(original, null, creator, false, i -> segProducer.apply(original, i), i -> segProducer.apply(mask, i).getLowerSegmentValue(), false);
	}

	@Override
	public IPAddressSection adjustPrefixBySegment(boolean nextSegment) {
		int prefix = getAdjustedPrefix(nextSegment, getBitsPerSegment(), false);
		Integer existing = getNetworkPrefixLength();
		if(existing == null) {
			if(nextSegment ? prefix == getBitCount() : prefix == 0) {
				return this;
			}
		} else if(existing != null && existing == prefix && prefix != 0) {
			//remove the prefix from the end
			return removePrefixLength();
		}
		return setPrefixLength(prefix);
	}

	@Override
	public abstract IPAddressSection adjustPrefixLength(int adjustment);
	
	protected static <R extends IPAddressSection, S extends IPAddressSegment> IPAddressSection adjustPrefixLength(
			R original, int adjustment, IPAddressNetwork<?, R, ?, S, ?>.IPAddressCreator creator, SegFunction<R, S> segProducer) {
		if(adjustment == 0) {
			return original;
		}
		int prefix = original.getAdjustedPrefix(adjustment, false, false);
		if(prefix > original.getBitCount()) {
			if(!original.isPrefixed()) {
				return original;
			}
			int maskBitCount = original.getPrefixLength();
			IPAddressNetwork<?, R, ?, S, ?> network = creator.getNetwork();
			R mask = network.getNetworkMaskSection(maskBitCount);
			return getSubnetSegments(original, null, creator, false, i -> segProducer.apply(original, i), i -> segProducer.apply(mask, i).getLowerSegmentValue(), false);
		}
		if(prefix < 0) {
			prefix = 0;
		}
		return original.setPrefixLength(prefix);
	}

	@Override
	public abstract IPAddressSection setPrefixLength(int prefixLength);

	@Override
	public abstract IPAddressSection setPrefixLength(int prefixLength, boolean zeroed);

	private boolean hasNoPrefixCache() {
		if(prefixCache == null) {
			synchronized(this) {
				if(prefixCache == null) {
					prefixCache = new PrefixCache();
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Returns the smallest CIDR prefix length possible (largest network) for which this includes the block of address sections for that prefix.
	 *
	 * @see inet.ipaddr.format.IPAddressDivision#getBlockMaskPrefixLength(boolean)
	 * 
	 * @return
	 */
	@Override
	public int getMinPrefixLengthForBlock() {
		Integer result;
		if(hasNoPrefixCache() || (result = prefixCache.cachedMinPrefix) == null) {
			prefixCache.cachedMinPrefix = result = super.getMinPrefixLengthForBlock();
		}
		return result;
	}
	
	/**
	 * Returns a prefix length for which the range of this address section matches the the block of addresses for that prefix.
	 * <p>
	 * If no such prefix exists, returns null
	 * <p>
	 * If this address section represents a single value, returns the bit length
	 * <p>
	 * @return the prefix length or null
	 */
	@Override
	public Integer getPrefixLengthForSingleBlock() {
		if(!hasNoPrefixCache()) {
			Integer result = prefixCache.cachedEquivalentPrefix;
			if(result != null) {
				if(result < 0) {
					return null;
				}
				return result;
			}
		}
		Integer res = super.getPrefixLengthForSingleBlock();
		if(res == null) {
			prefixCache.cachedEquivalentPrefix = NO_PREFIX_LENGTH;
			return null;
		}
		return prefixCache.cachedEquivalentPrefix = res;
	}

	@Override
	public abstract IPAddressSection getLowerNonZeroHost();
	
	@Override
	public abstract IPAddressSection getLower();
	
	@Override
	public abstract IPAddressSection getUpper();
	
	@Override
	public abstract IPAddressSection toZeroHost();

	@Override
	public abstract IPAddressSection reverseSegments();
	
	@Override
	public abstract IPAddressSection reverseBits(boolean perByte);
	
	@Override
	public abstract IPAddressSection reverseBytes();
	
	@Override
	public abstract IPAddressSection reverseBytesPerSegment();

	protected IPAddressSegment[] getSegmentsInternal() {
		return (IPAddressSegment[]) getDivisionsInternal();
	}

	@Override
	public abstract IPAddressSection getSection(int index);

	@Override
	public abstract IPAddressSection getSection(int index, int endIndex);

	@Override
	public void getSegments(AddressSegment segs[]) {
		getSegments(0, getDivisionCount(), segs, 0);
	}
	
	@Override
	public void getSegments(int start, int end, AddressSegment segs[], int destIndex) {
		System.arraycopy(getDivisionsInternal(), start, segs, destIndex, end - start);
	}
	
	protected static <T extends IPAddress, R extends IPAddressSection, S extends IPAddressSegment> R createEmbeddedSection(
			IPAddressNetwork<T, R, ?, S, ?>.IPAddressCreator creator, S segs[], IPAddressSection encompassingSection) {
		return creator.createEmbeddedSectionInternal(encompassingSection, segs);
	}
	
	@Override
	public abstract Iterable<? extends IPAddressSection> getIterable();
	
	@Override
	public abstract Iterator<? extends IPAddressSection> nonZeroHostIterator();
	
	@Override
	public abstract Iterator<? extends IPAddressSection> iterator();
	
	public boolean isEntireAddress() {
		return getSegmentCount() == IPAddress.getSegmentCount(getIPVersion());
	}
	
	protected <S extends IPAddressSegment> boolean isZeroHost(S segments[]) {
		if(segments.length == 0 || !isPrefixed()) {
			return false;
		}
		int prefLen = getNetworkPrefixLength();
		if(prefLen >= getBitCount()) {
			return false;
		}
		int bitsPerSegment = getBitsPerSegment();
		int prefixedSegmentIndex = getHostSegmentIndex(prefLen, getBytesPerSegment(), bitsPerSegment);
		int divCount = segments.length;
		for(int i = prefixedSegmentIndex; i < divCount; i++) {
			Integer segmentPrefixLength = getPrefixedSegmentPrefixLength(bitsPerSegment, prefLen, prefixedSegmentIndex);
			S div = segments[i];
			if(segmentPrefixLength != null) {
				int mask = div.getSegmentHostMask(segmentPrefixLength);
				if((mask & div.getLowerSegmentValue()) != 0) {
					return false;
				}
				for(++i; i < divCount; i++) {
					div = segments[i];
					if(div.getLowerSegmentValue() != 0) {
						return false;
					}
				}
			}
		}
		return true;
	}
	
	InetAddress toInetAddress(IPAddress address) {
		InetAddress result;
		if(hasNoValueCache() || (result = valueCache.inetAddress) == null) {
			valueCache.inetAddress = result = address.toInetAddressImpl(getBytes());
		}
		return result;
	}
	
	InetAddress toUpperInetAddress(IPAddress address) {
		InetAddress result;
		if(hasNoValueCache() || (result = valueCache.upperInetAddress) == null) {
			valueCache.upperInetAddress = result = address.toInetAddressImpl(getUpperBytes());
		}
		return result;
	}
	
	////////////////string creation below ///////////////////////////////////////////////////////////////////////////////////////////

	static void checkLengths(int length, StringBuilder builder) {
		IPAddressStringParams.checkLengths(length, builder);
	}
	
	@Override
	public String toString() {
		return toNormalizedString();//for ipv6, the canonical string can be the same for two different sections
	}

	@Override
	public String[] getSegmentStrings() {
		return getDivisionStrings();
	}
	
	protected abstract void cacheNormalizedString(String str);
	
	protected abstract IPStringCache getStringCache();
	
	protected abstract boolean hasNoStringCache();
	
	/*
	 * There are two approaches when going beyond the usual segment by segment approach to producing strings for IPv6 and IPv4.
	 * We can use the inet_aton approach, creating new segments as desired (one, two or three segments instead of the usual 4).
	 * Then each such segment must simply know it's own sizes, whether bits, bytes, or characters, as IPAddressJoinedSegments and its subclasses show.
	 * The limitations to this are the fact that arithmetic is done with Java longs, limiting the possible sizes.  Also, we must define new classes to accommodate the new segments.
	 * A disadvantage to this approach is that the new segments may be short lived, so any caching is not helpful.
	 * 
	 * The second approach is to print with no separator chars (no '.' or ':') and with leading zeros, but otherwise print in the same manner.
	 * So 1:2 would become 00010002.  
	 * This works in cases where the string character boundaries line up with the segment boundaries.
	 * This works for hexadecimal, where each segment is exactly two characters for IPv4, and each segment is exactly 4 characters for IPv6.
	 * For other radices, this is not so simple.
	 * 
	 * A hybrid approach would use both approaches.  For instance, for octal we could simply divide into segments where each segment has 6 bits,
	 * corresponding to exactly two octal characters, or any combination where each segment has some multiple of 3 bits.  It helps if the segment bit length
	 * divides the total bit length, so the first segment does not end up with too many leading zeros.  
	 * In the cases where the above approaches do not work, this approach works.
	 */
	

	@Override
	public String toBinaryString() {
		String result;
		if(hasNoStringCache() || (result = getStringCache().binaryString) == null) {
			IPStringCache stringCache = getStringCache();
			stringCache.binaryString = result = toBinaryString(null);
		}
		return result;
	}
	
	protected String toBinaryString(CharSequence zone) {
		if(isDualString()) {
			return toNormalizedStringRange(toIPParams(IPStringCache.binaryParams), getLower(), getUpper(), zone);
		}
		return toIPParams(IPStringCache.binaryParams).toString(this, zone);
	}
	
	@Override
	public String toOctalString(boolean with0Prefix) {  
		String result;
		if(hasNoStringCache() || (result = (with0Prefix ? getStringCache().octalStringPrefixed : getStringCache().octalString)) == null) {
			IPStringCache stringCache = getStringCache();
			result = toOctalString(with0Prefix, null);
			if(with0Prefix) {
				stringCache.octalStringPrefixed = result;
			} else {
				stringCache.octalString = result;
			}
		}
		return result;
	}
	
	protected String toOctalString(boolean with0Prefix, CharSequence zone) {
		if(isDualString()) {
			IPAddressSection lower = getLower();
			IPAddressSection upper = getUpper();
			IPAddressBitsDivision lowerDivs[] = lower.createNewDivisions(3, IPAddressBitsDivision::new, IPAddressBitsDivision[]::new);
			IPAddressStringDivisionSeries lowerPart = new IPAddressDivisionGrouping(lowerDivs, getNetwork());
			IPAddressBitsDivision upperDivs[] = upper.createNewDivisions(3, IPAddressBitsDivision::new, IPAddressBitsDivision[]::new);
			IPAddressStringDivisionSeries upperPart = new IPAddressDivisionGrouping(upperDivs, getNetwork());
			return toNormalizedStringRange(toIPParams(with0Prefix ? IPStringCache.octalPrefixedParams : IPStringCache.octalParams), lowerPart, upperPart, zone);
		}
		IPAddressBitsDivision divs[] = createNewPrefixedDivisions(3, null, null, IPAddressBitsDivision::new, IPAddressBitsDivision[]::new);
		IPAddressStringDivisionSeries part = new IPAddressDivisionGrouping(divs, getNetwork());
		return toIPParams(with0Prefix ? IPStringCache.octalPrefixedParams : IPStringCache.octalParams).toString(part, zone);
	}

	@Override
	public String toHexString(boolean with0xPrefix) {  
		String result;
		if(hasNoStringCache() || (result = (with0xPrefix ? getStringCache().hexStringPrefixed : getStringCache().hexString)) == null) {
			IPStringCache stringCache = getStringCache();
			result = toHexString(with0xPrefix, null);
			if(with0xPrefix) {
				stringCache.hexStringPrefixed = result;
			} else {
				stringCache.hexString = result;
			}
		}
		return result;
	}
	
	//overridden in ipv6 to handle zone
	protected String toHexString(boolean with0xPrefix, CharSequence zone) {
		if(isDualString()) {
			return toNormalizedStringRange(toIPParams(with0xPrefix ? IPStringCache.hexPrefixedParams : IPStringCache.hexParams), getLower(), getUpper(), zone);
		}
		return toIPParams(with0xPrefix ? IPStringCache.hexPrefixedParams : IPStringCache.hexParams).toString(this, zone);
	}
	
	@Override
	public String toNormalizedString(IPStringOptions stringOptions) {
		return toNormalizedString(stringOptions, this);
	}

	public static String toNormalizedString(IPStringOptions opts, IPAddressStringDivisionSeries section) {
		return toIPParams(opts).toString(section);
	}
	
	protected static AddressStringParams<IPAddressStringDivisionSeries> toParams(IPStringOptions opts) {
		//since the params here are not dependent on the section, we could cache the params in the options 
		//this is not true on the IPv6 side where compression settings change based on the section
		
		@SuppressWarnings("unchecked")
		AddressStringParams<IPAddressStringDivisionSeries> result = (AddressStringParams<IPAddressStringDivisionSeries>) getCachedParams(opts);
		if(result == null) {
			result = new IPAddressStringParams<IPAddressStringDivisionSeries>(opts.base, opts.separator, opts.uppercase);
			result.expandSegments(opts.expandSegments);
			result.setWildcards(opts.wildcards);
			result.setSegmentStrPrefix(opts.segmentStrPrefix);
			result.setAddressLabel(opts.addrLabel);
			result.setReverse(opts.reverse);
			result.setSplitDigits(opts.splitDigits);
			result.setRadix(opts.base);
			result.setUppercase(opts.uppercase);
			result.setSeparator(opts.separator);
			result.setZoneSeparator(opts.zoneSeparator);
			setCachedParams(opts, result);
		}
		return result;
	}

	protected static IPAddressStringParams<IPAddressStringDivisionSeries> toIPParams(IPStringOptions opts) {
		//since the params here are not dependent on the section, we could cache the params in the options 
		//this is not true on the IPv6 side where compression settings change based on the section
		
		@SuppressWarnings("unchecked")
		IPAddressStringParams<IPAddressStringDivisionSeries> result = (IPAddressStringParams<IPAddressStringDivisionSeries>) getCachedParams(opts);
			
		if(result == null) {
			result = new IPAddressStringParams<IPAddressStringDivisionSeries>(opts.base, opts.separator, opts.uppercase);
			result.expandSegments(opts.expandSegments);
			result.setWildcards(opts.wildcards);
			result.setWildcardOption(opts.wildcardOption);
			result.setSegmentStrPrefix(opts.segmentStrPrefix);
			result.setAddressSuffix(opts.addrSuffix);
			result.setAddressLabel(opts.addrLabel);
			result.setReverse(opts.reverse);
			result.setSplitDigits(opts.splitDigits);
			result.setZoneSeparator(opts.zoneSeparator);
			setCachedParams(opts, result);
		}
		return result;
	}

		
	/**
	 * Returns at most a couple dozen string representations:
	 * 
	 * -mixed (1:2:3:4:5:6:1.2.3.4)
	 * -upper and lower case
	 * -full compressions or no compression (a:0:0:c:d:0:e:f or a::c:d:0:e:f or a:0:b:c:d::e:f)
	 * -full leading zeros (000a:0000:000b:000c:000d:0000:000e:000f)
	 * -combinations thereof
	 * 
	 * @return
	 */
	public IPAddressPartStringCollection toStandardStringCollection() {
		return toStringCollection(new IPStringBuilderOptions(IPStringBuilderOptions.LEADING_ZEROS_FULL_ALL_SEGMENTS));
	}

	/**
	 * Use this method with care...  a single IPv6 address can have thousands of string representations.
	 * 
	 * Examples: 
	 * "::" has 1297 such variations, but only 9 are considered standard
	 * "a:b:c:0:d:e:f:1" has 1920 variations, but only 12 are standard
	 * 
	 * Variations included in this method:
	 * -all standard variations
	 * -choosing specific segments for full leading zeros (::a:b can be ::000a:b, ::a:000b, or ::000a:000b)
	 * -choosing which zero-segments to compress (0:0:a:: can be ::a:0:0:0:0:0 or 0:0:a::)
	 * -mixed representation (1:2:3:4:5:6:1.2.3.4)
	 * -all combinations of such variations
	 * 
	 * Variations omitted from this method: 
	 * -mixed case of a-f, which you can easily handle yourself with String.equalsIgnoreCase
	 * -adding a variable number of leading zeros (::a can be ::0a, ::00a, ::000a)
	 * -choosing any number of zero-segments anywhere to compress (:: can be 0:0:0::0:0)
	 * 
	 * @return
	 */
	public IPAddressPartStringCollection toAllStringCollection() {
		return toStringCollection(new IPStringBuilderOptions(IPStringBuilderOptions.LEADING_ZEROS_FULL_SOME_SEGMENTS));
	}
	
	/**
	 * Returns a set of strings for search the standard string representations in a database
	 * 
	 * -compress the largest compressible segments or no compression (a:0:0:c:d:0:e:f or a::c:d:0:e:f)
	 * -upper/lowercase is not considered because many databases are case-insensitive
	 * 
	 * @return
	 */
	public IPAddressPartStringCollection toDatabaseSearchStringCollection() {
		return toStringCollection(new IPStringBuilderOptions());
	}
	
	/**
	 * Get all representations of this address including this IPAddressSection.  
	 * This includes:
	 * <ul>
	 * <li>alternative segment groupings expressed as {@link IPAddressDivisionGrouping}</li>
	 * <li>conversions to IPv6, and alternative representations of those IPv6 addresses</li>
	 * </ul>
	 * 
	 * @param options
	 * @return
	 */
	public IPAddressStringDivisionSeries[] getParts(IPStringBuilderOptions options) {
		if(options.includes(IPStringBuilderOptions.BASIC)) {
			return new IPAddressStringDivisionSeries[] { this };
		}
		return EMPTY_PARTS;
	}
	
	/**
	 * This method gives you an SQL clause that allows you to search the database for the front part of an address or 
	 * addresses in a given network.
	 * 
	 * This is not as simple as it sounds, because the same address can be written in different ways (especially for IPv6)
	 * and in addition, addresses in the same network can have different beginnings (eg 1.0.0.0/7 are all addresses from 0.0.0.0 to 1.255.255.255),
	 * so you can see they start with both 1 and 0.  You can reduce the number of possible beginnings by choosing a segment
	 * boundary for the network prefix.
	 * 
	 * The SQL produced works for MySQL.  For a different database type, 
	 * use {@link #getStartsWithSQLClause(StringBuilder, String, IPAddressSQLTranslator)}
	 * 
	 * @param builder
	 * @param expression the expression that must match the condition, whether a column name or other
	 */
	public void getStartsWithSQLClause(StringBuilder builder, String expression) {
		getStartsWithSQLClause(builder, expression, new MySQLTranslator());
	}
	
	public void getStartsWithSQLClause(StringBuilder builder, String expression, IPAddressSQLTranslator translator) {
		getStartsWithSQLClause(builder, expression, true, translator);
	}
	
	private void getStartsWithSQLClause(StringBuilder builder, String expression, boolean isFirstCall, IPAddressSQLTranslator translator) {
		if(isFirstCall && isMultiple()) {
			Iterator<? extends IPAddressSection> sectionIterator = iterator();
			builder.append('(');
			boolean isNotFirst = false;
			while(sectionIterator.hasNext()) {
				if(isNotFirst) {
					builder.append(" OR ");
				} else {
					isNotFirst = true;
				}
				IPAddressSection next = sectionIterator.next();
				next.getStartsWithSQLClause(builder, expression, false, translator);
			}
			builder.append(')');
		} else if(getSegmentCount() > 0) { //there is something to match and we are searching for an exact network prefix	
			IPAddressPartStringCollection createdStringCollection = toDatabaseSearchStringCollection();
			boolean isNotFirst = false;
			if(createdStringCollection.size() > 1) {
				builder.append('(');
			}
			boolean isEntireAddress = isEntireAddress();
			//for every string representation of our address section in the collection, we get an SQL clause that will match it
			for(IPAddressPartConfiguredString<?, ?> createdStr: createdStringCollection) {
				if(isNotFirst) {
					builder.append(" OR ");
				} else {
					isNotFirst = true;
				}
				SQLStringMatcher<?, ?, ?> matcher = createdStr.getNetworkStringMatcher(isEntireAddress, translator);
				matcher.getSQLCondition(builder.append('('), expression).append(')');
			}
			if(createdStringCollection.size() > 1) {
				builder.append(')');
			}
		}
	}

	/* the various string representations - these fields are for caching */
	protected static class IPStringCache extends StringCache {
		public static final IPStringOptions hexParams;
		public static final IPStringOptions hexPrefixedParams;
		public static final IPStringOptions octalParams;
		public static final IPStringOptions octalPrefixedParams;
		public static final IPStringOptions binaryParams;
		public static final IPStringOptions canonicalSegmentParams;

		static {
			WildcardOptions allWildcards = new WildcardOptions(WildcardOptions.WildcardOption.ALL);
			hexParams = new IPStringOptions.Builder(16).setSeparator(null).setExpandedSegments(true).setWildcardOptions(allWildcards).toOptions();
			hexPrefixedParams = new IPStringOptions.Builder(16).setSeparator(null).setExpandedSegments(true).setWildcardOptions(allWildcards).setAddressLabel(IPAddress.HEX_PREFIX).toOptions();
			octalParams = new IPStringOptions.Builder(8).setSeparator(null).setExpandedSegments(true).setWildcardOptions(allWildcards).toOptions();
			octalPrefixedParams = new IPStringOptions.Builder(8).setSeparator(null).setExpandedSegments(true).setWildcardOptions(allWildcards).setAddressLabel(IPAddress.OCTAL_PREFIX).toOptions();
			binaryParams = new IPStringOptions.Builder(2).setSeparator(null).setExpandedSegments(true).setWildcardOptions(allWildcards).toOptions();
			canonicalSegmentParams = new IPStringOptions.Builder(10, ' ').toOptions();
		}
		
		public String normalizedWildcardString;
		public String fullString;
		public String sqlWildcardString;

		public String reverseDNSString;
		
		public String octalStringPrefixed;
		public String octalString;
		public String binaryString;
	}
	
	public static class WildcardOptions {
		public enum WildcardOption {
			NETWORK_ONLY, //only print wildcards that are part of the network portion (only possible with subnet address notation, otherwise this option is ignored)
			ALL //print wildcards for any visible (non-compressed) segments
		}
		
		public final WildcardOption wildcardOption;
		public final Wildcards wildcards;
		
		public WildcardOptions() {
			this(WildcardOption.NETWORK_ONLY);
		}
		
		public WildcardOptions(WildcardOption wildcardOption) {
			this(wildcardOption, new Wildcards());
		}
		
		public WildcardOptions(WildcardOption wildcardOption, Wildcards wildcards) {
			this.wildcardOption = wildcardOption;
			this.wildcards = wildcards;
		}
	}

	/**
	 * Represents a clear way to create a specific type of string.
	 * 
	 * @author sfoley
	 */
	public static class IPStringOptions extends StringOptions {
		
		public final String addrSuffix;
		public final WildcardOption wildcardOption;
		public final char zoneSeparator;
		
		protected IPStringOptions(
				int base,
				boolean expandSegments,
				WildcardOption wildcardOption,
				Wildcards wildcards,
				String segmentStrPrefix,
				Character separator,
				char zoneSeparator,
				String label,
				String suffix,
				boolean reverse,
				boolean splitDigits,
				boolean uppercase) {
			super(base, expandSegments, wildcards, segmentStrPrefix, separator, label, reverse, splitDigits, uppercase);
			this.addrSuffix = suffix;
			this.wildcardOption = wildcardOption;
			this.zoneSeparator = zoneSeparator;
		}
		
		public static class Builder extends StringOptions.Builder {

			protected String addrSuffix = "";
			protected WildcardOption wildcardOption = WildcardOption.NETWORK_ONLY;
			protected char zoneSeparator = IPv6Address.ZONE_SEPARATOR;
			
			public Builder(int base) {
				this(base, ' ');
			}
			
			protected Builder(int base, char separator) {
				super(base, separator);
			}
			
			@Override
			public Builder setWildcards(Wildcards wildcards) {
				return (Builder) super.setWildcards(wildcards);
			}
			
			/*
			 * .in-addr.arpa, .ip6.arpa, .ipv6-literal.net are examples of suffixes tacked onto the end of address strings
			 */
			public Builder setAddressSuffix(String suffix) {
				this.addrSuffix = suffix;
				return this;
			}
			
			public Builder setWildcardOptions(WildcardOptions wildcardOptions) {
				setWildcardOption(wildcardOptions.wildcardOption);
				return setWildcards(wildcardOptions.wildcards);
			}
			
			public Builder setWildcardOption(WildcardOption wildcardOption) {
				this.wildcardOption = wildcardOption;
				return this;
			}
			
			@Override
			public Builder setReverse(boolean reverse) {
				return (Builder) super.setReverse(reverse);
			}
			
			@Override
			public Builder setUppercase(boolean uppercase) {
				return (Builder) super.setUppercase(uppercase);
			}
			
			@Override
			public Builder setSplitDigits(boolean splitDigits) {
				return (Builder) super.setSplitDigits(splitDigits);
			}
			
			@Override
			public Builder setExpandedSegments(boolean expandSegments) {
				return (Builder) super.setExpandedSegments(expandSegments);
			}
			
			@Override
			public Builder setRadix(int base) {
				return (Builder) super.setRadix(base);
			}
			
			/*
			 * separates the divisions of the address, typically ':' or '.', but also can be null for no separator
			 */
			@Override
			public Builder setSeparator(Character separator) {
				return (Builder) super.setSeparator(separator);
			}
			
			public Builder setZoneSeparator(char separator) {
				this.zoneSeparator = separator;
				return this;
			}
			
			@Override
			public Builder setAddressLabel(String label) {
				return (Builder) super.setAddressLabel(label);
			}
			
			@Override
			public Builder setSegmentStrPrefix(String prefix) {
				return (Builder) super.setSegmentStrPrefix(prefix);
			}
			
			@Override
			public IPStringOptions toOptions() {
				return new IPStringOptions(base,
						expandSegments, wildcardOption, wildcards, segmentStrPrefix, separator, zoneSeparator, addrLabel, addrSuffix, reverse, splitDigits, uppercase);
			}
		}
	}
	
	/**
	 * This user-facing class is designed to be a clear way to create a collection of strings.
	 * 
	 * @author sfoley
	 *
	 */
	public static class IPStringBuilderOptions {
		public static final int BASIC = 0x1;//no compressions, lowercase only, no leading zeros, no mixed, no nothing
		
		public static final int LEADING_ZEROS_FULL_ALL_SEGMENTS = 0x10; //0001:0002:00ab:0abc::
		public static final int LEADING_ZEROS_FULL_SOME_SEGMENTS = 0x20 | LEADING_ZEROS_FULL_ALL_SEGMENTS; //1:0002:00ab:0abc::, 0001:2:00ab:0abc::, ...
		public static final int LEADING_ZEROS_PARTIAL_SOME_SEGMENTS = 0x40 | LEADING_ZEROS_FULL_SOME_SEGMENTS; //1:02:00ab:0abc::, 01:2:00ab:0abc::, ...

		public final int options;
		
		public IPStringBuilderOptions() {
			this(BASIC);
		}
		
		public IPStringBuilderOptions(int options) {
			this.options = options;
		}
		
		public boolean includes(int option) {
			return (option & options) == option;
		}
		
		public boolean includesAny(int option) {
			return (option & options) != 0;
		}
		
		@Override
		public String toString() {
			TreeMap<Integer, String> options = new TreeMap<>();
			Field fields[] = getClass().getFields();
			for(Field field: fields) {
				int modifiers = field.getModifiers();
				if(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers)) {
					try {
						int constant = field.getInt(null);
						String option = field.getName() + ": " + includes(constant) + System.lineSeparator();
						options.put(constant, option);
					} catch(IllegalAccessException e) {}
				}
			}
			Collection<String> values = options.values(); //the iterator for this Collection is sorted since we use a SortedMap
			StringBuilder builder = new StringBuilder();
			for(String val : values) {
				builder.append(val);
			}
			return builder.toString();
		}
	}
}
