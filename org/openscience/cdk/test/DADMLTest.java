/* $RCSfile$
 * $Author$   
 * $Date$   
 * $Revision$
 * 
 * Copyright (C) 2001  The CDK project
 * 
 * Contact: steinbeck@ice.mpg.de, gezelter@maul.chem.nd.edu, egonw@sci.kun.nl
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 
 */

package org.openscience.cdk.test;

import org.openscience.cdk.*;
import org.openscience.cdk.io.*;
import org.openscience.cdk.renderer.*;
import org.openscience.cdk.tools.*;
import org.openscience.cdk.geometry.*;
import org.openscience.cdk.internet.*;
import java.util.*;
import java.io.*;

public class DADMLTest {

    private org.openscience.cdk.tools.LoggingTool logger;

    public DADMLTest(String superdb, String type, String index) {
      logger = new org.openscience.cdk.tools.LoggingTool();

      try {
        ChemObjectReader reader;
        logger.info("SuperDB: " + superdb);
        logger.info("index type: " + type);
        logger.info("index: " + index);
		reader = new DADMLReader(superdb);
		((DADMLReader)reader).setQuery(type, index);
        Molecule m = (Molecule)reader.read((ChemObject)new Molecule());

        MoleculeViewer2D mv = new MoleculeViewer2D(m);
        mv.display();
        AtomicTable at = new AtomicTable(m);
        at.display();
      } catch(Exception exc) {
	      logger.error(exc.toString());
      }
    }
    
    public static void main(String[] args) {
      if (args.length == 3) {
        String superdb = args[0];
        String type = args[1];
        String index = args[2];
        new DADMLTest(superdb, type, index);
      } else {
        System.out.println("Syntax: DADMLTest <superdb> <index_type> <index>");
      }
    }
}

