package org.broadinstitute.hellbender.tools.copynumber;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.programgroups.CopyNumberProgramGroup;
import org.broadinstitute.hellbender.engine.GATKTool;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.copynumber.arguments.CopyNumberArgumentValidationUtils;
import org.broadinstitute.hellbender.tools.copynumber.formats.collections.*;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.LocatableMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.SimpleSampleLocatableMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.records.CopyNumberPosteriorDistribution;
import org.broadinstitute.hellbender.tools.copynumber.formats.records.LinearCopyRatio;
import org.broadinstitute.hellbender.tools.copynumber.formats.records.IntervalCopyNumberGenotypingData;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.GermlineCNVIntervalVariantComposer;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.GermlineCNVNamingConstants;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.GermlineCNVSegmentVariantComposer;
import org.broadinstitute.hellbender.tools.copynumber.gcnv.IntegerCopyNumberState;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.io.IOUtils;
import org.broadinstitute.hellbender.utils.io.Resource;
import org.broadinstitute.hellbender.utils.python.PythonScriptExecutor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Postprocesses the output of {@link GermlineCNVCaller} and generates VCF files as well as a concatenated denoised
 * copy ratio file.
 *
 * <p>This tool generates "intervals" and "segments" VCF files that serve complementary purposes. The intervals VCF
 * file provides a detailed listing of the most likely copy-number call for each genomic interval included in the
 * call-set, along with call quality, call genotype, and the phred-scaled posterior probability vector for all
 * integer copy-number states. Given that CNV events often span several consecutive intervals, it may be desirable
 * to coalesce contiguous intervals with the same copy-number call into a constant copy-number segments. This tool
 * further performs segmentation and genotyping by calling a dedicated python script in `gcnvkernel`. The segmentation
 * algorithm further provides various quality metrics for the segment.</p>
 *
 * <p>For both VCF outputs, the CNV genotype is determined as follows: the alternative allele for a CNV call is
 * either <code>&lt;DEL&gt;</code> or <code>&lt;DUP&gt;</code>, depending on whether the most likely
 * copy-number call is below or above the reference copy-number for the contig. The user may specify the
 * reference copy-number state on autosomal contigs using the argument <code>autosomal-ref-copy-number</code>.
 * The list of allosomal contigs may also be specified via the argument <code>allosomal-contig</code>. All
 * undeclared contigs are assumed to be autosomal. The reference copy-number on an allosomal contig is determined
 * by the sex karyotype of the sample and is set to the pre-determined contig ploidy state fetched from the output
 * calls of {@link DetermineGermlineContigPloidy}.</p>
 *
 * <p>Finally, the tool concatenates posterior means for denoised copy ratios from all the call shards produced by
 * the {@link GermlineCNVCaller} into a single file. </p>
 *
 * <h3>Required inputs:</h3>
 * <ul>
 *     <li>A list of paths to {@link GermlineCNVCaller} calls shards</li>
 *     <li>A list of paths to {@link GermlineCNVCaller} model shards</li>
 *     <li>Path to the output calls of {@link DetermineGermlineContigPloidy}</li>
 *     <li>Index of the sample in the call-set (which is expected to be the same across all shards)</li>
 *     <li>Output path for writing the intervals VCF</li>
 *     <li>Output path for writing the segments VCF</li>
 *     <li>Output path for writing the concatenated denoised copy ratios</li>
 * </ul>
 *
 * <p>The calls or model shards can be specified in arbitrary order.</p>
 *
 * <h3>Usage example</h3>
 *
 * <pre>
 *   gatk PostprocessGermlineCNVCalls \
 *     --calls-shard-path path/to/shard_1-calls
 *     --calls-shard-path path/to/shard_2-calls
 *     --model-shard-path path/to/shard_1-model
 *     --model-shard-path path/to/shard_2-model
 *     --sample-index 0
 *     --autosomal-ref-copy-number 2
 *     --allosomal-contig X
 *     --allosomal-contig Y
 *     --output-genotyped-intervals sample_0_genotyped_intervals.vcf
 *     --output-genotyped-segments sample_0_genotyped_segments.vcf
 *     --output-denoised-copy-ratios sample_0_denoised_copy_ratios.tsv
 * </pre>
 *
 * @author Mehrtash Babadi &lt;mehrtash@broadinstitute.org&gt;
 * @author Andrey Smirnov &lt;asmirnov@broadinstitute.org&gt;
 */
@CommandLineProgramProperties(
        summary = "Postprocesses the output of GermlineCNVCaller and generates VCFs and denoised copy ratios",
        oneLineSummary = "Postprocesses the output of GermlineCNVCaller and generates VCFs and denoised copy ratios",
        programGroup = CopyNumberProgramGroup.class
)
@DocumentedFeature
public final class PostprocessGermlineCNVCalls extends GATKTool {
    public static final String SEGMENT_GERMLINE_CNV_CALLS_PYTHON_SCRIPT = "segment_gcnv_calls.py";

    public static final String CALLS_SHARD_PATH_LONG_NAME = "calls-shard-path";
    public static final String MODEL_SHARD_PATH_LONG_NAME = "model-shard-path";
    public static final String CONTIG_PLOIDY_CALLS_LONG_NAME = "contig-ploidy-calls";
    public static final String SAMPLE_INDEX_LONG_NAME = "sample-index";
    public static final String OUTPUT_INTERVALS_VCF_LONG_NAME = "output-genotyped-intervals";
    public static final String OUTPUT_SEGMENTS_VCF_LONG_NAME = "output-genotyped-segments";
    public static final String OUTPUT_DENOISED_COPY_RATIOS_LONG_NAME = "output-denoised-copy-ratios";
    public static final String AUTOSOMAL_REF_COPY_NUMBER_LONG_NAME = "autosomal-ref-copy-number";
    public static final String ALLOSOMAL_CONTIG_LONG_NAME = "allosomal-contig";

    @Argument(
            doc = "List of paths to GermlineCNVCaller call directories.",
            fullName = CALLS_SHARD_PATH_LONG_NAME,
            minElements = 1
    )
    private List<File> inputUnsortedCallsShardPaths;

    @Argument(
            doc = "List of paths to GermlineCNVCaller model directories.",
            fullName = MODEL_SHARD_PATH_LONG_NAME,
            minElements = 1
    )
    private List<File> inputUnsortedModelShardPaths;

    @Argument(
            doc = "Path to contig-ploidy calls directory (output of DetermineGermlineContigPloidy).",
            fullName = CONTIG_PLOIDY_CALLS_LONG_NAME
    )
    private File inputContigPloidyCallsPath;

    @Argument(
            doc = "Sample index in the call-set (must be contained in all shards).",
            fullName = SAMPLE_INDEX_LONG_NAME,
            minValue = 0
    )
    private int sampleIndex;

    @Argument(
            doc = "Reference copy-number on autosomal intervals.",
            fullName = AUTOSOMAL_REF_COPY_NUMBER_LONG_NAME,
            minValue = 0
    )
    private int refAutosomalCopyNumber = 2;

    @Argument(
            doc = "Contigs to treat as allosomal (i.e. choose their reference copy-number allele according to " +
                    "the sample karyotype).",
            fullName = ALLOSOMAL_CONTIG_LONG_NAME,
            optional = true
    )
    private List<String> allosomalContigList;

    @Argument(
            doc = "Output intervals VCF file.",
            fullName = OUTPUT_INTERVALS_VCF_LONG_NAME
    )
    private File outputIntervalsVCFFile;

    @Argument(
            doc = "Output segments VCF file.",
            fullName = OUTPUT_SEGMENTS_VCF_LONG_NAME
    )
    private File outputSegmentsVCFFile;

    @Argument(
            doc = "Output denoised copy ratio file.",
            fullName = OUTPUT_DENOISED_COPY_RATIOS_LONG_NAME
    )
    private File outputDenoisedCopyRatioFile;

    /**
     * A list of {@link SimpleIntervalCollection} for each shard
     */
    private List<SimpleIntervalCollection> sortedIntervalCollections;

    /**
     * Sequence dictionary that should be equivalent in each shard
     */
    private SAMSequenceDictionary sequenceDictionary;

    /**
     * The sample name corresponding to the provided sample index
     */
    private String sampleName;

    /**
     * Number of shards
     */
    private int numShards;

    /**
     * Reference integer copy-number state for autosomal intervals
     */
    private IntegerCopyNumberState refAutosomalIntegerCopyNumberState;

    /**
     * Set of allosomal contigs
     */
    private Set<String> allosomalContigSet;

    /**
     * Call shard directories put in correct order
     */
    private List<File> sortedCallsShardPaths;

    /**
     * Model shard directories put in correct order
     */
    private List<File> sortedModelShardPaths;

    @Override
    public void onStartup() {
        super.onStartup();
        /* check for successful import of gcnvkernel */
        PythonScriptExecutor.checkPythonEnvironmentForPackage("gcnvkernel");
    }

    /**
     * Performs various validations on input arguments. Since many of these validations requires loading and parsing
     * reusable data, we store them as global variables (shard interval lists, sample name, etc).
     */
    @Override
    public void onTraversalStart() {
        validateArguments();

        numShards = inputUnsortedCallsShardPaths.size();

        /* get intervals from each call and model shard in the provided (potentially arbitrary) order */
        final List<SimpleIntervalCollection> unsortedIntervalCollectionsFromCalls =
                getIntervalCollectionsFromPaths(inputUnsortedCallsShardPaths);
        final List<SimpleIntervalCollection> unsortedIntervalCollectionsFromModels =
                getIntervalCollectionsFromPaths(inputUnsortedModelShardPaths);

        /* assert that all shards have the same SAM sequence dictionary */
        sequenceDictionary = unsortedIntervalCollectionsFromCalls.get(0)
                .getMetadata().getSequenceDictionary();
        Utils.validateArg(unsortedIntervalCollectionsFromCalls.stream()
                        .map(SimpleIntervalCollection::getMetadata)
                        .map(LocatableMetadata::getSequenceDictionary)
                        .allMatch(shardSAMSequenceDictionary ->
                                shardSAMSequenceDictionary.equals(sequenceDictionary)),
                "The SAM sequence dictionary is not the same for all of the call shards.");
        Utils.validateArg(unsortedIntervalCollectionsFromModels.stream()
                        .map(SimpleIntervalCollection::getMetadata)
                        .map(LocatableMetadata::getSequenceDictionary)
                        .allMatch(shardSAMSequenceDictionary ->
                                shardSAMSequenceDictionary.equals(sequenceDictionary)),
                "The SAM sequence dictionary is either not the same for all of the model shards, " +
                        "or is different from the SAM sequence dictionary of calls shards.");

        /* get the correct shard sort order and sort all collections */
        final List<Integer> sortedCallShardsOrder = AbstractLocatableCollection.getShardedCollectionSortOrder(
                unsortedIntervalCollectionsFromCalls);
        final List<Integer> sortedModelShardsOrder = AbstractLocatableCollection.getShardedCollectionSortOrder(
                unsortedIntervalCollectionsFromModels);
        sortedCallsShardPaths = sortedCallShardsOrder.stream()
                .map(inputUnsortedCallsShardPaths::get)
                .collect(Collectors.toList());
        sortedModelShardPaths = sortedModelShardsOrder.stream()
                .map(inputUnsortedModelShardPaths::get)
                .collect(Collectors.toList());
        final List<SimpleIntervalCollection> sortedIntervalCollectionsFromCalls = sortedCallShardsOrder.stream()
                .map(unsortedIntervalCollectionsFromCalls::get)
                .collect(Collectors.toList());
        final List<SimpleIntervalCollection> sortedIntervalCollectionsFromModels = sortedModelShardsOrder.stream()
                .map(unsortedIntervalCollectionsFromModels::get)
                .collect(Collectors.toList());
        Utils.validateArg(sortedIntervalCollectionsFromCalls.equals(sortedIntervalCollectionsFromModels),
                "The interval lists found in model and call shards do not match. Make sure that the calls and model " +
                        "paths are provided in matching order.");
        sortedIntervalCollections = sortedIntervalCollectionsFromCalls;

        /* assert that allosomal contigs are contained in the SAM sequence dictionary */
        final Set<String> allContigs = sequenceDictionary.getSequences().stream()
                .map(SAMSequenceRecord::getSequenceName)
                .collect(Collectors.toSet());
        allosomalContigSet = new HashSet<>(allosomalContigList);
        if (allosomalContigSet.isEmpty()) {
            logger.warn(String.format("Allosomal contigs were not specified; setting ref copy-number allele " +
                    "to (%d) for all intervals.", refAutosomalCopyNumber));
        } else {
            Utils.validateArg(allContigs.containsAll(allosomalContigSet), String.format(
                    "The specified allosomal contigs must be contained in the SAM sequence dictionary of the " +
                            "call-set (specified allosomal contigs: %s, all contigs: %s)",
                    allosomalContigSet.stream().collect(Collectors.joining(", ", "[", "]")),
                    allContigs.stream().collect(Collectors.joining(", ", "[", "]"))));
        }

        /* get sample name from the first shard and assert that all shards have the same sample name */
        sampleName = getShardSampleName(0);
        Utils.validate(IntStream.range(1, numShards)
                        .mapToObj(this::getShardSampleName)
                        .allMatch(shardSampleName -> shardSampleName.equals(sampleName)),
                "The sample name is not the same for all of the shards.");

        refAutosomalIntegerCopyNumberState = new IntegerCopyNumberState(refAutosomalCopyNumber);
    }

    private void validateArguments() {
        Utils.validateArg(inputUnsortedCallsShardPaths.size() == inputUnsortedModelShardPaths.size(),
                "The number of input call shards must match the number of input model shards.");

        inputUnsortedCallsShardPaths.forEach(CopyNumberArgumentValidationUtils::validateInputs);
        inputUnsortedModelShardPaths.forEach(CopyNumberArgumentValidationUtils::validateInputs);
        CopyNumberArgumentValidationUtils.validateInputs(inputContigPloidyCallsPath);
        CopyNumberArgumentValidationUtils.validateOutputFiles(
                outputIntervalsVCFFile,
                outputSegmentsVCFFile,
                outputDenoisedCopyRatioFile);
    }

    @Override
    public void traverse() {}  // no traversal for this tool

    @Override
    public Object onTraversalSuccess() {
        generateIntervalsVCFFileFromAllShards();
        generateSegmentsVCFFileFromAllShards();
        concatenateDenoisedCopyRatioFiles();
        logger.info(String.format("%s complete.", getClass().getSimpleName()));

        return null;
    }

    private void generateIntervalsVCFFileFromAllShards() {
        logger.info("Generating intervals VCF file...");
        final VariantContextWriter intervalsVCFWriter = createVCFWriter(outputIntervalsVCFFile);

        final GermlineCNVIntervalVariantComposer germlineCNVIntervalVariantComposer =
                new GermlineCNVIntervalVariantComposer(intervalsVCFWriter, sampleName,
                        refAutosomalIntegerCopyNumberState, allosomalContigSet);
        germlineCNVIntervalVariantComposer.composeVariantContextHeader(getDefaultToolVCFHeaderLines());

        logger.info(String.format("Writing intervals VCF file to %s...", outputIntervalsVCFFile.getAbsolutePath()));
        for (int shardIndex = 0; shardIndex < numShards; shardIndex++) {
            logger.info(String.format("Analyzing shard %d / %d...", shardIndex, numShards));
            germlineCNVIntervalVariantComposer.writeAll(getShardIntervalCopyNumberPosteriorData(shardIndex));
        }
        intervalsVCFWriter.close();
    }

    private void generateSegmentsVCFFileFromAllShards() {
        logger.info("Generating segments VCF file...");

        /* perform segmentation */
        final File pythonScriptOutputPath = IOUtils.createTempDir("gcnv-segmented-calls");
        final boolean pythonScriptSucceeded = executeSegmentGermlineCNVCallsPythonScript(
                sampleIndex, inputContigPloidyCallsPath, sortedCallsShardPaths, sortedModelShardPaths,
                pythonScriptOutputPath);
        if (!pythonScriptSucceeded) {
            throw new UserException("Python return code was non-zero.");
        }

        /* parse segments */
        final File copyNumberSegmentsFile = getCopyNumberSegmentsFile(pythonScriptOutputPath, sampleIndex);
        final IntegerCopyNumberSegmentCollection integerCopyNumberSegmentCollection =
                new IntegerCopyNumberSegmentCollection(copyNumberSegmentsFile);
        final String sampleNameFromSegmentCollection = integerCopyNumberSegmentCollection
                .getMetadata().getSampleName();
        Utils.validate(sampleNameFromSegmentCollection.equals(sampleName),
                String.format("Sample name found in the header of copy-number segments file is " +
                                "different from the expected sample name (found: %s, expected: %s).",
                        sampleNameFromSegmentCollection, sampleName));

        /* write variants */
        logger.info(String.format("Writing segments VCF file to %s...", outputSegmentsVCFFile.getAbsolutePath()));
        final VariantContextWriter segmentsVCFWriter = createVCFWriter(outputSegmentsVCFFile);
        final GermlineCNVSegmentVariantComposer germlineCNVSegmentVariantComposer =
                new GermlineCNVSegmentVariantComposer(segmentsVCFWriter, sampleName,
                        refAutosomalIntegerCopyNumberState, allosomalContigSet);
        germlineCNVSegmentVariantComposer.composeVariantContextHeader(getDefaultToolVCFHeaderLines());
        germlineCNVSegmentVariantComposer.writeAll(integerCopyNumberSegmentCollection.getRecords());
        segmentsVCFWriter.close();
    }

    private void concatenateDenoisedCopyRatioFiles() {
        logger.info("Generating denoised copy ratios...");
        final List<SimpleInterval> concatenatedIntervalList = new ArrayList<>();
        final List<Double> concatenatedDenoisedCopyRatioRecordsList = new ArrayList<>();
        /* Read in and concatenate all denoised copy ratio files into one list */
        for (int shardIndex = 0; shardIndex < numShards; shardIndex++) {
            final File shardRootDirectory = sortedCallsShardPaths.get(shardIndex);
            final File denoisedCopyRatioFile = getSampleDenoisedCopyRatioFile(shardRootDirectory, sampleIndex);
            final NonLocatableDoubleCollection shardNonLocatableLinearCopyRatioCollectionForShard = new NonLocatableDoubleCollection(denoisedCopyRatioFile);
            final List<SimpleInterval> shardIntervals = sortedIntervalCollections.get(shardIndex).getIntervals();
            final String sampleNameFromDenoisedCopyRatioFile = shardNonLocatableLinearCopyRatioCollectionForShard
                    .getMetadata().getSampleName();
            Utils.validate(sampleNameFromDenoisedCopyRatioFile.equals(sampleName),
                    String.format("Sample name found in the header of denoised copy ratio file for shard %d " +
                                    "is different from the expected sample name (found: %s, expected: %s).",
                            shardIndex, sampleNameFromDenoisedCopyRatioFile, sampleName));
            final List<Double> shardDenoisedCopyRatioRecords = shardNonLocatableLinearCopyRatioCollectionForShard.getRecords();
            Utils.validate(shardIntervals.size() == shardDenoisedCopyRatioRecords.size(),
                    String.format("The number of entries in denoised copy ratio file for shard %d does " +
                                    "not match the number of entries in the shard interval list (copy ratio list size: %d, " +
                                    "interval list size: %d)",
                            shardIndex, shardDenoisedCopyRatioRecords.size(), shardIntervals.size()));
            concatenatedIntervalList.addAll(shardIntervals);
            concatenatedDenoisedCopyRatioRecordsList.addAll(shardDenoisedCopyRatioRecords);
        }
        /* Attach the corresponding intervals */
        final List<LinearCopyRatio> linearCopyRatioList =
                IntStream.range(0, concatenatedIntervalList.size())
                        .mapToObj(intervalIndex -> new LinearCopyRatio(
                                concatenatedIntervalList.get(intervalIndex),
                                concatenatedDenoisedCopyRatioRecordsList.get(intervalIndex)))
                        .collect(Collectors.toList());
        final SimpleSampleLocatableMetadata metadata = new SimpleSampleLocatableMetadata(sampleName, sequenceDictionary);
        /* Make a locatable collection of denoised copy ratios and write it to file */
        final LinearCopyRatioCollection linearCopyRatioCollection =
                new LinearCopyRatioCollection(metadata, linearCopyRatioList);
        logger.info(String.format("Writing denoised copy ratios to %s...", outputDenoisedCopyRatioFile.getAbsolutePath()));
        linearCopyRatioCollection.write(outputDenoisedCopyRatioFile);
    }

    /**
     * Retrieves intervals either from a `gcnvkernel` model output path or the root directory of calls path.
     */
    private static List<SimpleIntervalCollection> getIntervalCollectionsFromPaths(final List<File> shardsPathList) {
        return shardsPathList.stream()
                .map(shardPath -> new SimpleIntervalCollection(
                        getIntervalFileFromShardDirectory(shardPath)))
                .collect(Collectors.toList());
    }

    /**
     * Extracts sample name from a designated text file in the sample calls directory.
     */
    private String getShardSampleName(final int shardIndex) {
        final File shardSampleNameTextFile = getSampleNameTextFile(sortedCallsShardPaths.get(shardIndex),
                sampleIndex);
        try {
            final BufferedReader reader = new BufferedReader(new FileReader(shardSampleNameTextFile));
            return reader.readLine();
        } catch (final IOException ex) {
            throw new UserException.BadInput(String.format("Could not read the sample name text file at %s.",
                    shardSampleNameTextFile.getAbsolutePath()));
        }
    }

    /**
     * Returns a list of {@link IntervalCopyNumberGenotypingData} for {@link #sampleIndex} from a
     * single calls shard.
     */
    private List<IntervalCopyNumberGenotypingData> getShardIntervalCopyNumberPosteriorData(final int shardIndex) {
        /* read copy-number posteriors for the shard */
        final File shardRootDirectory = sortedCallsShardPaths.get(shardIndex);
        final File sampleCopyNumberPosteriorFile = getSampleCopyNumberPosteriorFile(shardRootDirectory, sampleIndex);
        final CopyNumberPosteriorDistributionCollection copyNumberPosteriorDistributionCollection =
                new CopyNumberPosteriorDistributionCollection(sampleCopyNumberPosteriorFile);
        final String sampleNameFromCopyNumberPosteriorFile = copyNumberPosteriorDistributionCollection
                .getMetadata().getSampleName();
        Utils.validate(sampleNameFromCopyNumberPosteriorFile.equals(sampleName),
                String.format("Sample name found in the header of copy-number posterior file for shard %d " +
                        "different from the expected sample name (found: %s, expected: %s).",
                        shardIndex, sampleNameFromCopyNumberPosteriorFile, sampleName));

        /* read copy-number baselines for the shard */
        final File sampleBaselineCopyNumberFile = getSampleBaselineCopyNumberFile(shardRootDirectory, sampleIndex);
        final BaselineCopyNumberCollection baselineCopyNumberCollection = new BaselineCopyNumberCollection(sampleBaselineCopyNumberFile);
        final String sampleNameFromBaselineCopyNumberFile = baselineCopyNumberCollection
                .getMetadata().getSampleName();
        Utils.validate(sampleNameFromBaselineCopyNumberFile.equals(sampleName),
                String.format("Sample name found in the header of baseline copy-number file for shard %d " +
                                "different from the expected sample name (found: %s, expected: %s).",
                        shardIndex, sampleNameFromBaselineCopyNumberFile, sampleName));

        /* attach the intervals to make locatable posteriors */
        final List<SimpleInterval> shardIntervals = sortedIntervalCollections.get(shardIndex).getIntervals();
        final List<CopyNumberPosteriorDistribution> copyNumberPosteriorDistributionList =
                copyNumberPosteriorDistributionCollection.getRecords();
        Utils.validate(shardIntervals.size() == copyNumberPosteriorDistributionList.size(),
                String.format("The number of entries in the copy-number posterior file for shard %d does " +
                                "not match the number of entries in the shard interval list (posterior list size: %d, " +
                                "interval list size: %d)", shardIndex, copyNumberPosteriorDistributionList.size(),
                        shardIntervals.size()));

        final List<IntegerCopyNumberState> baselineCopyNumberList = baselineCopyNumberCollection.getRecords();
        Utils.validate(shardIntervals.size() == baselineCopyNumberList.size(),
                String.format("The number of entries in the baseline copy-number file for shard %d does " +
                                "not match the number of entries in the shard interval list (baseline copy-number " +
                                "list size: %d, interval list size: %d)", shardIndex, baselineCopyNumberList.size(),
                        shardIntervals.size()));

        return IntStream.range(0, copyNumberPosteriorDistributionCollection.size())
                        .mapToObj(intervalIndex -> new IntervalCopyNumberGenotypingData(
                                shardIntervals.get(intervalIndex),
                                copyNumberPosteriorDistributionList.get(intervalIndex),
                                baselineCopyNumberList.get(intervalIndex)))
                        .collect(Collectors.toList());
    }

    /**
     * Gets the copy-number posterior file from the shard directory for a given sample index.
     */
    private static File getSampleCopyNumberPosteriorFile(final File callShardPath, final int sampleIndex) {
        return Paths.get(
                callShardPath.getAbsolutePath(),
                GermlineCNVNamingConstants.SAMPLE_PREFIX + sampleIndex,
                GermlineCNVNamingConstants.COPY_NUMBER_POSTERIOR_FILE_NAME).toFile();
    }

    /**
     * Gets the baseline copy-number file from the shard directory for a given sample index.
     */
    private static File getSampleBaselineCopyNumberFile(final File callShardPath, final int sampleIndex) {
        return Paths.get(
                callShardPath.getAbsolutePath(),
                GermlineCNVNamingConstants.SAMPLE_PREFIX + sampleIndex,
                GermlineCNVNamingConstants.BASELINE_COPY_NUMBER_FILE_NAME).toFile();
    }

    /**
     * Gets the sample name text file from the shard directory for a given sample index.
     */
    private static File getSampleNameTextFile(final File callShardRootPath, final int sampleIndex) {
        return Paths.get(
                callShardRootPath.getAbsolutePath(),
                GermlineCNVNamingConstants.SAMPLE_PREFIX + sampleIndex,
                GermlineCNVNamingConstants.SAMPLE_NAME_TXT_FILE).toFile();
    }

    /**
     * Gets the denoised copy ratio file from the shard directory given sample index.
     */
    private static File getSampleDenoisedCopyRatioFile(final File callShardPath, final int sampleIndex) {
        return Paths.get(
                callShardPath.getAbsolutePath(),
                GermlineCNVNamingConstants.SAMPLE_PREFIX + sampleIndex,
                GermlineCNVNamingConstants.DENOISED_COPY_RATIO_MEAN_FILE_NAME).toFile();
    }
    /**
     * Gets the interval list file from the shard directory.
     */
    private static File getIntervalFileFromShardDirectory(final File shardPath) {
        return new File(shardPath, GermlineCNVNamingConstants.INTERVAL_LIST_FILE_NAME);
    }

    /**
     * Runs the segmenter python scripts and returns the exit code (true for a successful python call).
     */
    private static boolean executeSegmentGermlineCNVCallsPythonScript(final int sampleIndex,
                                                                      final File contigPloidyCallsPath,
                                                                      final List<File> sortedCallDirectories,
                                                                      final List<File> sortedModelDirectories,
                                                                      final File pythonScriptOutputPath) {
        /* the inputs to this method are expected to be previously validated */
        try {
            Utils.nonNull(contigPloidyCallsPath);
            Utils.nonNull(sortedCallDirectories);
            Utils.nonNull(sortedModelDirectories);
            Utils.nonNull(pythonScriptOutputPath);
            sortedCallDirectories.forEach(Utils::nonNull);
            sortedModelDirectories.forEach(Utils::nonNull);
        } catch (final IllegalArgumentException ex) {
            throw new GATKException.ShouldNeverReachHereException(ex);
        }

        final PythonScriptExecutor executor = new PythonScriptExecutor(true);
        final List<String> arguments = new ArrayList<>();
        arguments.add("--ploidy_calls_path");
        arguments.add(CopyNumberArgumentValidationUtils.getCanonicalPath(contigPloidyCallsPath));
        arguments.add("--model_shards");
        arguments.addAll(sortedModelDirectories.stream().map(CopyNumberArgumentValidationUtils::getCanonicalPath).collect(Collectors.toList()));
        arguments.add("--calls_shards");
        arguments.addAll(sortedCallDirectories.stream().map(CopyNumberArgumentValidationUtils::getCanonicalPath).collect(Collectors.toList()));
        arguments.add("--output_path");
        arguments.add(CopyNumberArgumentValidationUtils.getCanonicalPath(pythonScriptOutputPath));
        arguments.add("--sample_index");
        arguments.add(String.valueOf(sampleIndex));

        return executor.executeScript(
                new Resource(SEGMENT_GERMLINE_CNV_CALLS_PYTHON_SCRIPT, PostprocessGermlineCNVCalls.class),
                null,
                arguments);
    }

    /**
     * Gets the copy-number segments file from the output of `segment_gcnv_calls.py` for a given sample index.
     */
    private static File getCopyNumberSegmentsFile(final File pythonSegmenterOutputPath, final int sampleIndex) {
        return Paths.get(
                pythonSegmenterOutputPath.getAbsolutePath(),
                GermlineCNVNamingConstants.SAMPLE_PREFIX + sampleIndex,
                GermlineCNVNamingConstants.COPY_NUMBER_SEGMENTS_FILE_NAME).toFile();
    }
}
