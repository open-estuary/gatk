package org.broadinstitute.hellbender.utils.genotyper;

import htsjdk.samtools.util.Locatable;
import org.broadinstitute.hellbender.utils.Utils;

import java.util.ArrayList;
import java.util.List;

public class GroupedEvidence<EVIDENCE extends Locatable>  extends ArrayList<EVIDENCE> implements Locatable {
    private static final long serialVersionUID = 934900L;

    private final String contig;
    private final int start;
    private final int end;

    public GroupedEvidence(final List<EVIDENCE> evidence) {
        super(evidence);
        Utils.nonEmpty(evidence, "Must have at least one unit of evidence");
        contig = evidence.get(0).getContig();
        final int minStart = evidence.stream().mapToInt(Locatable::getStart).min().getAsInt();
        final int minEnd = evidence.stream().mapToInt(Locatable::getEnd).min().getAsInt();
        final int maxEnd = evidence.stream().mapToInt(Locatable::getEnd).max().getAsInt();
        final int maxStart = evidence.stream().mapToInt(Locatable::getStart).max().getAsInt();
        start = minStart;
        end = maxEnd;
    }

    @Override
    public String getContig() { return contig; }

    @Override
    public int getStart() { return start; }

    @Override
    public int getEnd() { return end; }

}
