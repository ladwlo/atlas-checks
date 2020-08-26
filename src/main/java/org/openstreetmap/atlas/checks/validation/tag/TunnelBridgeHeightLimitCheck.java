package org.openstreetmap.atlas.checks.validation.tag;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.atlas.checks.base.BaseCheck;
import org.openstreetmap.atlas.checks.flag.CheckFlag;
import org.openstreetmap.atlas.geography.PolyLine;
import org.openstreetmap.atlas.geography.atlas.items.AtlasObject;
import org.openstreetmap.atlas.geography.atlas.items.Edge;
import org.openstreetmap.atlas.tags.BridgeTag;
import org.openstreetmap.atlas.tags.CoveredTag;
import org.openstreetmap.atlas.tags.HighwayTag;
import org.openstreetmap.atlas.tags.MaxHeightTag;
import org.openstreetmap.atlas.tags.TunnelTag;
import org.openstreetmap.atlas.tags.annotations.validation.Validators;
import org.openstreetmap.atlas.utilities.collections.Iterables;
import org.openstreetmap.atlas.utilities.configuration.Configuration;

/**
 * Flags highways (of certain classes) which should have a 'maxheight' or 'maxheight:*' tag but do
 * not have either. This is a port of Osmose check #7130.<br>
 * <b>Target objects:</b><br>
 * 1. Tunnels<br>
 * 2. Covered ways<br>
 * 3. Ways passing under bridges<br>
 * <b>Target highway classes:</b><br>
 * MOTORWAY_LINK, TRUNK_LINK, PRIMARY, PRIMARY_LINK, SECONDARY, SECONDARY_LINK
 *
 * @author wlodarsk
 */
public class TunnelBridgeHeightLimitCheck extends BaseCheck<Long>
{

    private static final long serialVersionUID = 7912181047816225229L;

    private static final String FALLBACK_INSTRUCTION_TEMPLATE = "Way {0,number,#} %s but vehicle height limit is not specified. Add a 'maxheight' or 'maxheight:physical' tag according to an existing legal or physical restriction.";
    private static final int TUNNEL_CASE_INDEX = 0;
    private static final int COVERED_CASE_INDEX = 1;
    private static final int BRIDGE_CASE_INDEX = 2;
    private static final List<String> FALLBACK_CASES = Arrays.asList("is a tunnel", "is covered",
            "passes under bridge ({1,number,#})");
    private static final List<String> FALLBACK_INSTRUCTIONS = FALLBACK_CASES.stream()
            .map(caseDescription -> String.format(FALLBACK_INSTRUCTION_TEMPLATE, caseDescription))
            .collect(Collectors.toList());
    private static final String MAXHEIGHT_PHYSICAL = "maxheight:physical";

    /**
     * Default constructor.
     *
     * @param configuration
     *            the JSON configuration for this check
     */
    public TunnelBridgeHeightLimitCheck(final Configuration configuration)
    {
        super(configuration);
    }

    /**
     * This function will validate if the supplied atlas object is valid for the check.
     *
     * @param object
     *            the atlas object supplied by the Atlas-Checks framework for evaluation
     * @return {@code true} if this object should be checked
     */
    @Override
    public boolean validCheckForObject(final AtlasObject object)
    {
        return object instanceof Edge && ((Edge) object).isMasterEdge()
                && !isFlagged(object.getOsmIdentifier());
    }

    /**
     * This is the actual function that will check to see whether the object needs to be flagged.
     *
     * @param object
     *            the atlas object supplied by the Atlas-Checks framework for evaluation
     * @return an optional {@link CheckFlag} object that
     */
    @Override
    protected Optional<CheckFlag> flag(final AtlasObject object)
    {
        // case 1 (tunnel) & 2 (covered highway)
        if ((TunnelTag.isTunnel(object) || isCovered(object)) && isHighwayWithoutMaxHeight(object))
        {
            final Long osmId = object.getOsmIdentifier();
            markAsFlagged(osmId);
            final int instructionIndex = TunnelTag.isTunnel(object) ? TUNNEL_CASE_INDEX
                    : COVERED_CASE_INDEX;
            return Optional
                    .of(createFlag(object, getLocalizedInstruction(instructionIndex, osmId)));
        }
        // case 3 (road passing under bridge)
        if (BridgeTag.isBridge(object))
        {
            final Edge bridge = (Edge) object;
            final PolyLine bridgeAsPolyLine = bridge.asPolyLine();
            final Set<Edge> edgesToFlag = new HashSet<>();
            Iterables.stream(bridge.getAtlas().edgesIntersecting(bridge.bounds()))
                    .filter(edge -> edge.isMasterEdge()
                            && edge.getOsmIdentifier() != bridge.getOsmIdentifier()
                            && !isFlagged(edge.getOsmIdentifier())
                            && isHighwayWithoutMaxHeight(edge)
                            && edgeCrossesBridge(edge.asPolyLine(), bridgeAsPolyLine))
                    .forEach(edge ->
                    {
                        markAsFlagged(edge.getOsmIdentifier());
                        edgesToFlag.add(edge);
                    });
            if (!edgesToFlag.isEmpty())
            {
                final CheckFlag checkFlag = new CheckFlag(getTaskIdentifier(bridge));
                edgesToFlag.forEach(
                        edge -> checkFlag.addObject(edge, getLocalizedInstruction(BRIDGE_CASE_INDEX,
                                edge.getOsmIdentifier(), bridge.getOsmIdentifier())));
                return Optional.of(checkFlag);
            }
        }
        return Optional.empty();
    }

    @Override
    protected List<String> getFallbackInstructions()
    {
        return FALLBACK_INSTRUCTIONS;
    }

    // check if the two polylines intersect at any location other than the bridge's endpoints
    private boolean edgeCrossesBridge(final PolyLine edge, final PolyLine bridge)
    {
        return edge.intersections(bridge).stream()
                .anyMatch(loc -> !loc.equals(bridge.first()) && !loc.equals(bridge.last()));
    }

    private boolean isCovered(final AtlasObject object)
    {
        return Validators.isOfType(object, CoveredTag.class, CoveredTag.YES, CoveredTag.ARCADE,
                CoveredTag.COLONNADE);
    }

    private boolean isHighwayWithoutMaxHeight(final AtlasObject object)
    {
        return Validators.isOfType(object, HighwayTag.class, HighwayTag.MOTORWAY_LINK,
                HighwayTag.TRUNK_LINK, HighwayTag.PRIMARY, HighwayTag.PRIMARY_LINK,
                HighwayTag.SECONDARY, HighwayTag.SECONDARY_LINK)
                && !Validators.hasValuesFor(object, MaxHeightTag.class)
                && object.getTag(MAXHEIGHT_PHYSICAL).isEmpty();
    }
}
