/* Copyright (C) 2006-2008  Egon Willighagen <egonw@sci.kun.nl>
 *
 * Contact: cdk-devel@lists.sourceforge.net
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.openscience.cdk.io;

import org.openscience.cdk.CDKConstants;
import org.openscience.cdk.config.Elements;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.interfaces.IAtom;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.interfaces.IBond;
import org.openscience.cdk.interfaces.IChemObject;
import org.openscience.cdk.interfaces.IChemObjectBuilder;
import org.openscience.cdk.interfaces.IElement;
import org.openscience.cdk.interfaces.IPseudoAtom;
import org.openscience.cdk.interfaces.ISingleElectron;
import org.openscience.cdk.interfaces.IStereoElement;
import org.openscience.cdk.io.formats.IResourceFormat;
import org.openscience.cdk.io.formats.MDLV3000Format;
import org.openscience.cdk.io.setting.BooleanIOSetting;
import org.openscience.cdk.io.setting.IOSetting;
import org.openscience.cdk.isomorphism.matchers.IQueryAtomContainer;
import org.openscience.cdk.isomorphism.matchers.IQueryBond;
import org.openscience.cdk.sgroup.Sgroup;
import org.openscience.cdk.sgroup.SgroupType;
import org.openscience.cdk.stereo.StereoElementFactory;
import org.openscience.cdk.tools.ILoggingTool;
import org.openscience.cdk.tools.LoggingToolFactory;
import org.openscience.cdk.tools.manipulator.BondManipulator;

import javax.vecmath.Point2d;
import javax.vecmath.Point3d;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class that implements the MDL mol V3000 format. This reader reads the
 * element symbol and 2D or 3D coordinates from the ATOM block.
 *
 * @author Egon Willighagen &lt;egonw@users.sf.net&gt;
 * @cdk.module io
 * @cdk.githash
 * @cdk.iooptions
 * @cdk.created 2006
 * @cdk.keyword MDL molfile V3000
 * @cdk.require java1.4+
 */
public class MDLV3000Reader extends DefaultChemObjectReader {

    private BooleanIOSetting optForce3d;
    private BooleanIOSetting optHydIso;
    private BooleanIOSetting optStereoPerc;
    private BooleanIOSetting optStereo0d;

    BufferedReader input;
    private static final ILoggingTool logger = LoggingToolFactory.createLoggingTool(MDLV3000Reader.class);

    private final Pattern keyValueTuple;
    private final Pattern keyValueTuple2;

    private int lineNumber;

    public MDLV3000Reader(Reader in) {
        this(in, Mode.RELAXED);
    }

    public MDLV3000Reader(Reader in, Mode mode) {
        input = new BufferedReader(in);
        initIOSettings();
        super.mode = mode;
        /* compile patterns */
        keyValueTuple = Pattern.compile("\\s*(\\w+)=([^\\s]*)(.*)"); // e.g. CHG=-1
        keyValueTuple2 = Pattern.compile("\\s*(\\w+)=\\(([^\\)]*)\\)(.*)"); // e.g. ATOMS=(1 31)
        lineNumber = 0;
    }

    public MDLV3000Reader(InputStream input) {
        this(input, Mode.RELAXED);
    }

    public MDLV3000Reader(InputStream input, Mode mode) {
        this(new InputStreamReader(input), mode);
    }

    public MDLV3000Reader() {
        this(new StringReader(""));
    }

    @Override
    public IResourceFormat getFormat() {
        return MDLV3000Format.getInstance();
    }

    @Override
    public void setReader(Reader input) throws CDKException {
        if (input instanceof BufferedReader) {
            this.input = (BufferedReader) input;
        } else {
            this.input = new BufferedReader(input);
        }
        lineNumber = 0;
    }

    @Override
    public void setReader(InputStream input) throws CDKException {
        setReader(new InputStreamReader(input));
    }

    @Override
    public boolean accepts(Class<? extends IChemObject> classObject) {
        Class<?>[] interfaces = classObject.getInterfaces();
        for (Class<?> anInterface : interfaces) {
            if (IAtomContainer.class.equals(anInterface)) return true;
        }
        if (IAtomContainer.class.equals(classObject)) return true;
        Class superClass = classObject.getSuperclass();
        if (superClass != null) return this.accepts(superClass);
        return false;
    }

    @Override
    public <T extends IChemObject> T read(T object) throws CDKException {
        if (object instanceof IAtomContainer) {
            return (T) readMolecule(object.getBuilder());
        }
        throw new CDKException("Only supports AtomContainer objects.");
    }

    private static final class ReadState {
        IAtomContainer mol;
        int dimensions = 0; // 0D (undef/no coordinates), 2D, 3D
        boolean chiral;
        Map<Integer,Integer> stereoflags = null;
        final Map<IAtom,Integer> stereo0d = new HashMap<>();

        // atom/bond ids need not be sequential, we could use map but more
        // common is the ids will be sequential
        IAtom[] atomById = new IAtom[64];
        IBond[] bondById = new IBond[64];

        <T> T[] grow(T[] arr, int req)
        {
            int cap = arr.length;
            return Arrays.copyOf(arr, Math.max(cap + cap >> 1,
                                               req + 1));
        }

        void addAtom(int id, IAtom atom) {
            if (id >= atomById.length)
                atomById = grow(atomById, id);
            atomById[id] = atom;
        }

        void addBond(int id, IBond bond) {
            if (id >= bondById.length)
                bondById = grow(bondById, id);
            bondById[id] = bond;
        }

        public IAtom getAtom(int i) {
            return atomById[i];
        }

        public IBond getBond(int i) {
            return bondById[i];
        }
    }

    public IAtomContainer readMolecule(IChemObjectBuilder builder) throws CDKException {
        return readConnectionTable(builder);
    }

    public IAtomContainer readConnectionTable(IChemObjectBuilder builder) throws CDKException {

        logger.info("Reading CTAB block");
        ReadState state = new ReadState();
        IAtomContainer readData = builder.newAtomContainer();
        state.mol = readData;
        state.chiral = false;

        boolean foundEND = false;
        String lastLine = readHeader(state);
        while (isReady() && !foundEND) {
            String command = readCommand(lastLine);
            logger.debug("command found: " + command);
            if ("END CTAB".equals(command)) {
                foundEND = true;
            } else if ("BEGIN CTAB".equals(command)) {
                // that's fine
            } else if (command.startsWith("COUNTS ")) {
                // COUNTS <natom> <nbond> <nsgroup> <n3dquery> <chiral> [REGNO=<regno>]
                String[] counts = command.split(" ");
                state.chiral = counts.length >= 6 && counts[5].equals("1");
            } else if ("BEGIN ATOM".equals(command)) {
                readAtomBlock(state);
            } else if ("BEGIN BOND".equals(command)) {
                readBondBlock(state);
            } else if ("BEGIN SGROUP".equals(command)) {
                readSGroup(state);
            } else if ("BEGIN COLLECTION".equals(command)) {
                readCollection(state);
            } else {
                logger.warn("Unrecognized command: " + command);
            }
            lastLine = readLine();
        }

        finalizeMol(state);

        return readData;
    }

    private void finalizeMol(ReadState state) {
        finalizeDimensions(state);

        IAtomContainer readData = state.mol;
        boolean isQuery = readData instanceof IQueryAtomContainer;

        for (IAtom atom : readData.atoms()) {
            int valence = 0;
            for (IBond bond : readData.getConnectedBondsList(atom)) {
                if (bond instanceof IQueryBond || bond.getOrder() == IBond.Order.UNSET) {
                    valence = -1;
                    break;
                } else {
                    valence += bond.getOrder().numeric();
                }
            }
            if (valence < 0) {
                isQuery = true;
                logger.warn("Cannot set valence for atom with query bonds"); // also counts aromatic bond as query
            } else {
                final int unpaired = readData.getConnectedSingleElectronsCount(atom);
                applyMDLValenceModel(atom, valence + unpaired, unpaired);
            }
        }

        if (!isQuery)
            finalizeStereochemistry(state, readData);
    }

    private void finalizeStereochemistry(ReadState state, IAtomContainer readData) {
        if (optStereoPerc.isSet()) {

            if (state.dimensions == 3) { // has 3D coordinates
                readData.setStereoElements(StereoElementFactory.using3DCoordinates(readData)
                                                               .createAll());
            } else if (state.dimensions == 2) { // has 2D coordinates (set as 2D coordinates)
                readData.setStereoElements(StereoElementFactory.using2DCoordinates(readData)
                                                               .createAll());
            } else if (state.dimensions == 0 && optStereo0d.isSet()) {
                // technically if a molecule is 2D/3D and has the CFG=1 or CFG=2
                // specified this gives us hints information but it's safer to
                // just use the coordinates or wedge bonds
                for (Map.Entry<IAtom, Integer> e : state.stereo0d.entrySet()) {
                    IStereoElement<IAtom,IAtom> stereoElement
                            = MDLV2000Reader.createStereo0d(state.mol, e.getKey(), e.getValue());
                    if (stereoElement != null)
                        state.mol.addStereoElement(stereoElement);
                }
            }

            if (state.stereoflags != null && !state.stereoflags.isEmpty()) {

                // work out the next available group, if we have &1, &2, etc then we choose &3
                // this is only needed if
                int defaultRacGrp = 0;
                if (!state.chiral) {
                    int max = 0;
                    for (Integer val : state.stereoflags.values()) {
                        if ((val & IStereoElement.GRP_TYPE_MASK) == IStereoElement.GRP_RAC) {
                            int num = val >>> IStereoElement.GRP_NUM_SHIFT;
                            if (num > max)
                                max = num;
                        }
                    }
                    defaultRacGrp = IStereoElement.GRP_RAC | (((max + 1) << IStereoElement.GRP_NUM_SHIFT));
                }

                for (IStereoElement<?, ?> se : readData.stereoElements()) {
                    if (se.getConfigClass() != IStereoElement.TH)
                        continue;
                    IAtom focus = (IAtom) se.getFocus();
                    if (focus.getID() == null)
                        continue;
                    int idx = Integer.parseInt(focus.getID());
                    Integer grpinfo = state.stereoflags.get(idx);
                    if (grpinfo != null)
                        se.setGroupInfo(grpinfo);
                    else if (!state.chiral)
                        se.setGroupInfo(defaultRacGrp);
                }
            } else if (!state.chiral) {
                // chiral flag not set which means this molecule is this stereoisomer "and" the enantiomer, mark all
                // Tetrahedral stereo as AND1 (&1)
                for (IStereoElement<?, ?> se : readData.stereoElements()) {
                    if (se.getConfigClass() == IStereoElement.TH) {
                        se.setGroupInfo(IStereoElement.GRP_RAC1);
                    }
                }
            }
        }
    }

    // the parser will read all coords as 3D, then given information
    // in the header and the x,y,z values of each atom we work out
    // whether we are 0D, 2D (Point2D) or 3D (Point3D)
    private void finalizeDimensions(ReadState state) {
        // all ready done
        if (state.dimensions == 3 || optForce3d.isSet())
            return;
        int dimensions = 0;
        for (IAtom atom : state.mol.atoms()) {
            Point3d p3d = atom.getPoint3d();
            if (p3d.z != 0d) {
                dimensions = 3; // 3D
                break;
            } else if (dimensions == 0 && p3d.x != 0 && p3d.y != 0) {
                dimensions = 2; // 2D (if not 3D)
            }
        }
        // check the global header
        if (dimensions == 0)
            dimensions = state.dimensions;
        state.dimensions = dimensions;

        if (dimensions == 0) {
            // remove all coords we set
            for (IAtom atom : state.mol.atoms())
                atom.setPoint3d(null);
        } else if (dimensions == 2) {
            // convert 3d to 2d
            for (IAtom atom : state.mol.atoms()) {
                Point3d p3d = atom.getPoint3d();
                atom.setPoint2d(new Point2d(p3d.x, p3d.y));
                atom.setPoint3d(null);
            }
        }
    }

    boolean isDigit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    private void parseStereoGroup(Map<Integer,Integer> flags, String str, int type) {
        int i   = "MDLV30/STE???".length();
        int len = str.length();
        int num = 0;
        char ch;

        while (i < len && isDigit(ch = str.charAt(i))) {
            num = 10 * num + (ch - '0');
            i++;
        }
        type |= num << IStereoElement.GRP_NUM_SHIFT;

        // skip space
        while (i < len && str.charAt(i) == ' ')
            i++;
        // start of atom list
        if (str.startsWith("ATOMS=(", i))
            i += "ATOMS=(".length();

        // skip the count since we're storing in map
        while (i < len && isDigit(str.charAt(i)))
            i++;
        while (i < len && str.charAt(i) == ' ')
            i++;

        // parse the atoms
        while (i < len) {
            int val = 0;
            while (i < len && isDigit(ch = str.charAt(i))) {
                val = 10 * val + (ch - '0');
                i++;
            }
            // val-1 since we store atom index instead of atom number
            if (val > 0)
                flags.put(val, type);
            while (i < len && str.charAt(i) == ' ')
                i++;
            if (i < len && str.charAt(i) == ')')
                break;
        }
    }

    /**
     * Read collection information: highlights (no currently supported) and abs, rac, rel stereo groups
     *
     * @param state the read state
     * @return true if stereo group info was set - this is to override the chiral flag
     */
    private void readCollection(ReadState state) throws CDKException {
        if (state.stereoflags == null)
            state.stereoflags = new HashMap<>();
        String line;
        while ((line = readLine()) != null) {
            String command = readCommand(line);
            if (command.startsWith("END COLLECTION"))
                break;
            else if (command.startsWith("MDLV30/STERAC")) {
                parseStereoGroup(state.stereoflags, command, IStereoElement.GRP_RAC);
            } else if (command.startsWith("MDLV30/STEREL")) {
                parseStereoGroup(state.stereoflags, command, IStereoElement.GRP_REL);
            } else if (command.startsWith("MDLV30/STEABS")) {
                parseStereoGroup(state.stereoflags, command, IStereoElement.GRP_ABS);
            }
        }
    }

    // read info from the info header
    //'  CDK     09251712073D'
    // 0123456789012345678901
    private static int parseDimensions(String info) {
        if (info.startsWith("2D", 20))
            return 2;
        if (info.startsWith("3D", 20))
            return 3;
        return 0;
    }

    /**
     * @return Last line read
     * @throws CDKException when no file content is detected
     */
    public String readHeader(ReadState state) throws CDKException {
        // read four lines
        String line1 = readLine();
        if (line1 == null) {
            throw new CDKException("Expected a header line, but found nothing.");
        }
        if (line1.length() > 0) {
            if (line1.startsWith("M  V30")) {
                // no header
                return line1;
            }
            state.mol.setTitle(line1);
        }
        String infoLine = readLine();
        state.dimensions = parseDimensions(infoLine);
        String line3 = readLine();
        if (line3.length() > 0)
            state.mol.setProperty(CDKConstants.COMMENT, line3);
        String line4 = readLine();
        if (!line4.contains("3000")) {
            throw new CDKException("This file is not a MDL V3000 molfile.");
        }
        return readLine();
    }

    /**
     * Reads the atoms, coordinates and charges.
     *
     * <p>IMPORTANT: it does not support the atom list and its negation!
     */
    public void readAtomBlock(ReadState state) throws CDKException {
        IAtomContainer readData = state.mol;
        logger.info("Reading ATOM block");

        int RGroupCounter = 1;
        int Rnumber;
        String id;
        String[] rGroup;

        boolean foundEND = false;
        while (isReady() && !foundEND) {
            String command = readCommand(readLine());
            if ("END ATOM".equals(command)) {
                // FIXME: should check whether 3D is really 2D
                foundEND = true;
            } else {
                logger.debug("Parsing atom from: " + command);
                IAtom atom = readData.getBuilder().newAtom();
                StringTokenizer tokenizer = new StringTokenizer(command);
                // parse the index
                try {
                    id = tokenizer.nextToken();
                } catch (Exception exception) {
                    String error = "Error while parsing atom index";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }
                // parse the element
                String element = tokenizer.nextToken();
                Elements e = Elements.ofString(element);
                if (e != Elements.Unknown) {
                    atom.setAtomicNumber(e.number());
                } else if ("D".equals(element) && optHydIso.isSet()) {
                    atom.setMassNumber(2);
                    atom.setAtomicNumber(IElement.H);
                } else if ("T".equals(element) && optHydIso.isSet()) {
                    atom.setMassNumber(3);
                    atom.setAtomicNumber(IElement.H);
                } else if ("A".equals(element)) {
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else if ("Q".equals(element)) {
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else if ("*".equals(element)) {
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else if ("LP".equals(element)) {
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else if ("L".equals(element)) {
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else if (element.length() > 0 && element.charAt(0) == 'R') {
                    logger.debug("Atom ", element, " is not an regular element. Creating a PseudoAtom.");
                    //check if the element is R
                    rGroup = element.split("^R");
                    if (rGroup.length > 1) {
                        try {
                            Rnumber = Integer.parseInt(rGroup[(rGroup.length - 1)]);
                            RGroupCounter = Rnumber;
                        } catch (Exception ex) {
                            Rnumber = RGroupCounter;
                            RGroupCounter++;
                        }
                        element = "R" + Rnumber;
                    }
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                } else {
                    if (mode == ISimpleChemObjectReader.Mode.STRICT) {
                        throw new CDKException(
                                "Invalid element type. Must be an existing element, or one in: A, Q, L, LP, *.");
                    }
                    atom = readData.getBuilder().newInstance(IPseudoAtom.class, element);
                    atom.setSymbol(element);
                }

                // parse atom coordinates (in Angstrom)
                try {
                    String xString = tokenizer.nextToken();
                    String yString = tokenizer.nextToken();
                    String zString = tokenizer.nextToken();
                    double x = Double.parseDouble(xString);
                    double y = Double.parseDouble(yString);
                    double z = Double.parseDouble(zString);
                    atom.setPoint3d(new Point3d(x, y, z));
                } catch (Exception exception) {
                    String error = "Error while parsing atom coordinates";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }
                // atom-atom mapping
                String mapping = tokenizer.nextToken();
                if (!mapping.equals("0")) {
                    logger.warn("Skipping atom-atom mapping: " + mapping);
                } // else: default 0 is no mapping defined

                // the rest are key value things
                if (command.indexOf('=') != -1) {
                    Map<String, String> options = parseOptions(exhaustStringTokenizer(tokenizer));
                    for (String key : options.keySet()) {
                        String value = options.get(key);
                        try {
                            switch (key) {
                                case "CFG":
                                    int cfg = Integer.parseInt(value);
                                    if (cfg != 0) {
                                        atom.setStereoParity(cfg);
                                        state.stereo0d.put(atom, cfg);
                                    }
                                    break;
                                case "CHG":
                                    int charge = Integer.parseInt(value);
                                    if (charge != 0) { // zero is no charge specified
                                        atom.setFormalCharge(charge);
                                    }
                                    break;
                                case "RAD":
                                    MDLV2000Writer.SPIN_MULTIPLICITY spinMultiplicity = MDLV2000Writer.SPIN_MULTIPLICITY.ofValue(Integer.parseInt(value));
                                    int numElectons = spinMultiplicity.getSingleElectrons();
                                    atom.setProperty(CDKConstants.SPIN_MULTIPLICITY, spinMultiplicity);
                                    while (numElectons-- > 0) {
                                        readData.addSingleElectron(readData.getBuilder()
                                                                           .newInstance(ISingleElectron.class, atom));
                                    }
                                    break;
                                case "MASS":
                                    atom.setMassNumber(Integer.parseInt(value));
                                    break;
                                case "VAL":
                                    if (!(atom instanceof IPseudoAtom)) {
                                        try {
                                            int valence = Integer.parseInt(value);
                                            if (valence != 0) {
                                                //15 is defined as 0 in mol files
                                                if (valence == 15)
                                                    atom.setValency(0);
                                                else
                                                    atom.setValency(valence);
                                            }
                                        } catch (Exception exception) {
                                            handleError("Could not parse valence information field", lineNumber, 0, 0, exception);
                                        }
                                    } else {
                                        logger.error("Cannot set valence information for a non-element!");
                                    }
                                    break;
                                default:
                                    logger.warn("Not parsing key: " + key);
                                    break;
                            }
                        } catch (Exception exception) {
                            String error = "Error while parsing key/value " + key + "=" + value + ": "
                                    + exception.getMessage();
                            logger.error(error);
                            logger.debug(exception);
                            throw new CDKException(error, exception);
                        }
                    }
                }

                // store atom
                atom.setID(id);
                readData.addAtom(atom);
                state.addAtom(Integer.parseInt(id), readData.getAtom(readData.getAtomCount()-1));
                logger.debug("Added atom: " + atom);
            }
        }
    }

    /**
     * Reads the bond atoms, order and stereo configuration.
     */
    public void readBondBlock(ReadState state) throws CDKException {
        IAtomContainer readData = state.mol;
        logger.info("Reading BOND block");
        boolean foundEND = false;
        while (isReady() && !foundEND) {
            String command = readCommand(readLine());
            if ("END BOND".equals(command)) {
                foundEND = true;
            } else {
                logger.debug("Parsing bond from: " + command);
                StringTokenizer tokenizer = new StringTokenizer(command);
                IBond bond = readData.getBuilder().newBond();
                // parse the index
                try {
                    String indexString = tokenizer.nextToken();
                    bond.setID(indexString);
                } catch (Exception exception) {
                    String error = "Error while parsing bond index";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }
                // parse the order
                try {
                    String orderString = tokenizer.nextToken();
                    int order = Integer.parseInt(orderString);
                    if (order >= 4) {
                        bond.setOrder(IBond.Order.UNSET);
                        logger.warn("Query order types are not supported (yet). File a bug if you need it");
                    } else {
                        bond.setOrder(BondManipulator.createBondOrder(order));
                    }
                } catch (Exception exception) {
                    String error = "Error while parsing bond index";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }
                // parse index atom 1
                try {
                    String indexAtom1String = tokenizer.nextToken();
                    int indexAtom1 = Integer.parseInt(indexAtom1String);
                    IAtom atom1 = state.getAtom(indexAtom1);
                    bond.setAtom(atom1, 0);
                } catch (Exception exception) {
                    String error = "Error while parsing index atom 1 in bond";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }
                // parse index atom 2
                try {
                    String indexAtom2String = tokenizer.nextToken();
                    int indexAtom2 = Integer.parseInt(indexAtom2String);
                    IAtom atom2 = state.getAtom(indexAtom2);
                    bond.setAtom(atom2, 1);
                } catch (Exception exception) {
                    String error = "Error while parsing index atom 2 in bond";
                    logger.error(error);
                    logger.debug(exception);
                    throw new CDKException(error, exception);
                }

                List<IAtom> endpts = new ArrayList<>();
                String attach = null;

                // the rest are key=value fields
                if (command.indexOf('=') != -1) {
                    Map<String, String> options = parseOptions(exhaustStringTokenizer(tokenizer));
                    for (String key : options.keySet()) {
                        String value = options.get(key);
                        try {
                            switch (key) {
                                case "CFG":
                                    int configuration = Integer.parseInt(value);
                                    if (configuration == 0) {
                                        bond.setStereo(IBond.Stereo.NONE);
                                    } else if (configuration == 1) {
                                        bond.setStereo(IBond.Stereo.UP);
                                    } else if (configuration == 2) {
                                        bond.setStereo(IBond.Stereo.UP_OR_DOWN);
                                    } else if (configuration == 3) {
                                        bond.setStereo(IBond.Stereo.DOWN);
                                    }
                                    break;
                                case "ENDPTS":
                                    String[] endptStr = value.split(" ");
                                    // skip first value that is count
                                    for (int i = 1; i < endptStr.length; i++) {
                                        endpts.add(readData.getAtom(Integer.parseInt(endptStr[i]) - 1));
                                    }
                                    break;
                                case "ATTACH":
                                    attach = value;
                                    break;
                                default:
                                    logger.warn("Not parsing key: " + key);
                                    break;
                            }
                        } catch (Exception exception) {
                            String error = "Error while parsing key/value " + key + "=" + value + ": "
                                    + exception.getMessage();
                            logger.error(error);
                            logger.debug(exception);
                            throw new CDKException(error, exception);
                        }
                    }
                }

                // storing bond
                readData.addBond(bond);
                state.addBond(Integer.parseInt(bond.getID()),
                              readData.getBond(readData.getBondCount()-1));

                // storing positional variation
                if ("ANY".equals(attach)) {
                    Sgroup sgroup = new Sgroup();
                    sgroup.setType(SgroupType.ExtMulticenter);
                    sgroup.addAtom(bond.getBegin()); // could be other end?
                    sgroup.addBond(bond);
                    for (IAtom endpt : endpts)
                        sgroup.addAtom(endpt);

                    List<Sgroup> sgroups = readData.getProperty(CDKConstants.CTAB_SGROUPS);
                    if (sgroups == null)
                        readData.setProperty(CDKConstants.CTAB_SGROUPS, sgroups = new ArrayList<>(4));
                    sgroups.add(sgroup);
                }

                logger.debug("Added bond: " + bond);
            }
        }
    }

    /**
     * Reads labels.
     */
    public void readSGroup(ReadState state) throws CDKException {
        IAtomContainer readData = state.mol;
        boolean foundEND = false;
        while (isReady() && !foundEND) {
            String command = readCommand(readLine());
            if ("END SGROUP".equals(command)) {
                foundEND = true;
            } else {
                logger.debug("Parsing Sgroup line: " + command);
                StringTokenizer tokenizer = new StringTokenizer(command);
                // parse the index
                String indexString = tokenizer.nextToken();
                logger.warn("Skipping external index: " + indexString);
                // parse command type
                String type = tokenizer.nextToken();
                // parse the external index
                String externalIndexString = tokenizer.nextToken();
                logger.warn("Skipping external index: " + externalIndexString);

                // the rest are key=value fields
                Map<String, String> options = new Hashtable<>();
                if (command.indexOf('=') != -1) {
                    options = parseOptions(exhaustStringTokenizer(tokenizer));
                }

                Sgroup sgroup = new Sgroup();
                // now interpret line
                if (type.startsWith("SUP")) {
                    sgroup.setType(SgroupType.CtabAbbreviation);
                    Iterator<String> keys = options.keySet().iterator();
                    String label = "";
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = options.get(key);
                        try {
                            if (key.equals("ATOMS")) {
                                StringTokenizer atomsTokenizer = new StringTokenizer(value);
                                int nExpected = Integer.parseInt(atomsTokenizer.nextToken());
                                while (atomsTokenizer.hasMoreTokens()) {
                                    sgroup.addAtom(state.getAtom(Integer.parseInt(atomsTokenizer.nextToken())));
                                }
                            } else if (key.equals("XBONDS")) {
                                StringTokenizer xbonds = new StringTokenizer(value);
                                int nExpected = Integer.parseInt(xbonds.nextToken());
                                while (xbonds.hasMoreTokens()) {
                                    sgroup.addBond(state.getBond(Integer.parseInt(xbonds.nextToken())));
                                }
                            } else if (key.equals("LABEL")) {
                                label = value;
                            } else {
                                logger.warn("Not parsing key: " + key);
                            }
                        } catch (Exception exception) {
                            String error = "Error while parsing key/value " + key + "=" + value + ": "
                                    + exception.getMessage();
                            logger.error(error);
                            logger.debug(exception);
                            throw new CDKException(error, exception);
                        }
                        if (!sgroup.getAtoms().isEmpty() && label.length() > 0) {
                            sgroup.setSubscript(label);
                        }
                    }
                    List<Sgroup> sgroups = readData.getProperty(CDKConstants.CTAB_SGROUPS);
                    if (sgroups == null)
                        sgroups = new ArrayList<>();
                    sgroups.add(sgroup);
                    readData.setProperty(CDKConstants.CTAB_SGROUPS,
                            sgroups);
                } else {
                    logger.warn("Skipping unrecognized SGROUP type: " + type);
                }
            }
        }
    }

    /**
     * Reads the command on this line. If the line is continued on the next, that
     * part is added.
     *
     * @return Returns the command on this line.
     */
    private String readCommand(String line) throws CDKException {
        if (line.startsWith("M  V30 ")) {
            String command = line.substring(7);
            if (command.endsWith("-")) {
                command = command.substring(0, command.length() - 1);
                command += readCommand(readLine());
            }
            return command;
        } else {
            throw new CDKException("Could not read MDL file: unexpected line: " + line);
        }
    }

    private Map<String, String> parseOptions(String string) throws CDKException {
        Map<String, String> keyValueTuples = new Hashtable<>();
        while (string.length() >= 3) {
            logger.debug("Matching remaining option string: " + string);
            Matcher tuple1Matcher = keyValueTuple2.matcher(string);
            if (tuple1Matcher.matches()) {
                String key = tuple1Matcher.group(1);
                String value = tuple1Matcher.group(2);
                string = tuple1Matcher.group(3);
                logger.debug("Found key: " + key);
                logger.debug("Found value: " + value);
                keyValueTuples.put(key, value);
            } else {
                Matcher tuple2Matcher = keyValueTuple.matcher(string);
                if (tuple2Matcher.matches()) {
                    String key = tuple2Matcher.group(1);
                    String value = tuple2Matcher.group(2);
                    string = tuple2Matcher.group(3);
                    logger.debug("Found key: " + key);
                    logger.debug("Found value: " + value);
                    keyValueTuples.put(key, value);
                } else {
                    logger.warn("Quiting; could not parse: " + string + ".");
                    string = "";
                }
            }
        }
        return keyValueTuples;
    }

    public String exhaustStringTokenizer(StringTokenizer tokenizer) {
        StringBuilder buffer = new StringBuilder();
        buffer.append(' ');
        while (tokenizer.hasMoreTokens()) {
            buffer.append(tokenizer.nextToken());
            buffer.append(' ');
        }
        return buffer.toString();
    }

    public String readLine() throws CDKException {
        String line;
        try {
            line = input.readLine();
            lineNumber++;
            logger.debug("read line " + lineNumber + ":", line);
        } catch (Exception exception) {
            String error = "Unexpected error while reading file: " + exception.getMessage();
            logger.error(error);
            logger.debug(exception);
            throw new CDKException(error, exception);
        }
        return line;
    }

    public boolean isReady() throws CDKException {
        try {
            return input.ready();
        } catch (Exception exception) {
            String error = "Unexpected error while reading file: " + exception.getMessage();
            logger.error(error);
            logger.debug(exception);
            throw new CDKException(error, exception);
        }
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    private void initIOSettings() {
        optForce3d = addSetting(new BooleanIOSetting("ForceReadAs3DCoordinates", IOSetting.Importance.LOW,
                "Should coordinates always be read as 3D?", "false"));
        optHydIso = addSetting(new BooleanIOSetting("InterpretHydrogenIsotopes",
                IOSetting.Importance.LOW, "Should D and T be interpreted as hydrogen isotopes?", "true"));
        optStereoPerc = addSetting(new BooleanIOSetting("AddStereoElements", IOSetting.Importance.LOW,
                "Detect and create IStereoElements for the input.", "true"));
        optStereo0d = addSetting(new BooleanIOSetting("AddStereo0d", IOSetting.Importance.LOW,
                "Allow stereo created from parity value when no coordinates", "true"));

    }

    /**
     * Applies the MDL valence model to atoms using the explicit valence (bond
     * order sum) and charge to determine the correct number of implicit
     * hydrogens. The model is not applied if the explicit valence is less than
     * 0 - this is the case when a query bond was read for an atom.
     *
     * @param atom            the atom to apply the model to
     * @param explicitValence the explicit valence (bond order sum)
     */
    private void applyMDLValenceModel(IAtom atom, int explicitValence, int unpaired) {

        if (atom.getValency() != null) {
            if (atom.getValency() >= explicitValence)
                atom.setImplicitHydrogenCount(atom.getValency() - (explicitValence - unpaired));
            else
                atom.setImplicitHydrogenCount(0);
        } else {
            Integer element = atom.getAtomicNumber();
            if (element == null) element = 0;

            Integer charge = atom.getFormalCharge();
            if (charge == null) charge = 0;

            int implicitValence = MDLValence.implicitValence(element, charge, explicitValence);
            if (implicitValence < explicitValence) {
                atom.setValency(explicitValence);
                atom.setImplicitHydrogenCount(0);
            } else {
                atom.setValency(implicitValence);
                atom.setImplicitHydrogenCount(implicitValence - explicitValence);
            }
        }
    }

}
