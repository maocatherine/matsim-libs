/* *********************************************************************** *
 * project: org.matsim.*
 * SecondFilter.java
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2007 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

/**
 * 
 */
package playground.yu.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.matsim.utils.io.IOUtils;

/**
 * @author yu
 * 
 */
public class SecondFilter extends TableSplitter {
	private final BufferedWriter writer;

	public SecondFilter(final String regex, final String attTableFilename,
			final String outputFilename) throws IOException {
		super(regex, attTableFilename);
		writer = IOUtils.getBufferedWriter(outputFilename);
	}

	protected static boolean isHead(final String line) {
		return line.startsWith("$P");
	}

	static class timeIntervalReader extends TableSplitter {
		private final List<String> timeIntervalIndexs = new ArrayList<String>();
		private final List<String> minDepTimes = new ArrayList<String>();
		private final List<String> maxDepTimes = new ArrayList<String>();
		private final String attFilepath, outputAttFilepath;
		private int cnt = 0;

		public timeIntervalReader(final String regex,
				final String tableFileName, final String attFilepath,
				final String outputAttFilepath) throws IOException {
			super(regex, tableFileName);
			this.attFilepath = attFilepath;
			this.outputAttFilepath = outputAttFilepath;
		}

		public void makeParams(final String line) {
			if (line != null) {
				String[] params = split(line);
				timeIntervalIndexs.add(params[0]);
				minDepTimes.add(params[1]);
				maxDepTimes.add(params[2]);
				cnt++;
			}
		}

		public String getInputFilename(final int i) {
			return attFilepath + "MyFirstAttList22R24 ("
					+ timeIntervalIndexs.get(i) + ").att";
		}

		public String getOutputFilename(final int i) {
			return outputAttFilepath + "MyFirstAttList22R24 ("
					+ timeIntervalIndexs.get(i) + ").att";
		}

		public String getMinDepTime(final int i) {
			return minDepTimes.get(i);
		}

		public String getMaxDepTime(final int i) {
			return maxDepTimes.get(i);
		}

		/**
		 * @return the cnt
		 */
		public int getCnt() {
			return cnt;
		}
	}

	public static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

	public static timeIntervalReader readTimeInterval(
			final String timeIntervalFileName, final String attFilePath,
			final String outputFilePath) {
		timeIntervalReader tir = null;
		try {
			tir = new timeIntervalReader("\t", timeIntervalFileName,
					attFilePath, outputFilePath);
			String line = tir.readLine();
			while (line != null) {
				line = tir.readLine();
				tir.makeParams(line);
			}
			tir.closeReader();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		return tir;
	}

	private static class PrimLine {
		private final String origZoneNo, destZoneNo;
		private final String travelTime;
		private final int transfers;

		public PrimLine(final String origZoneNo, final String destZoneNo,
				final String travelTime, final String transfers)
				throws ParseException {
			this.origZoneNo = origZoneNo;
			this.destZoneNo = destZoneNo;
			// this.depTime = sdf.parse(depTime);
			this.travelTime = travelTime;
			this.transfers = Integer.parseInt(transfers);
		}

		public String getOrigZoneNo() {
			return origZoneNo;
		}

		public String getDestZoneNo() {
			return destZoneNo;
		}

		//
		// public Date getDepTime() {
		// return depTime;
		// }

		/**
		 * @return the travelTime
		 */
		public String getTravelTime() {
			return travelTime;
		}

		public int getTransfers() {
			return transfers;
		}

		public boolean sameODpair(final PrimLine pl) {
			return origZoneNo.equals(pl.getOrigZoneNo())
					&& destZoneNo.equals(pl.getDestZoneNo());
		}

		public boolean samePrimLine(final PrimLine pl) {
			return origZoneNo.equals(pl.getOrigZoneNo())
					&& destZoneNo.equals(pl.getDestZoneNo())
					&& transfers == pl.getTransfers()
					&& travelTime.equals(pl.getTravelTime());
		}
	}

	public void writeLine(final String line) {
		try {
			writer.write(line + "\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void writeNewLine(final String line) {
		try {
			String[] words = split(line);
			StringBuilder word = new StringBuilder();
			word.append(words[0]);
			for (int i = 1; i < words.length; i++) {
				word.append("\t");
				word.append(words[i]);
			}
			writer.write(word + "\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public void closeWriter() {
		try {
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @param args
	 */
	public static void main(final String[] args) {
		String timeIntervalFileName = "C:\\Users\\yalcin\\Desktop\\Zurich\\Marcel_code\\new\\DepTimeIndex22test.txt";
		String attFilePath = "C:\\Users\\yalcin\\Desktop\\Zurich\\Marcel_code\\new\\Att22R24\\";
		String outputFilePath = "C:\\Users\\yalcin\\Desktop\\Zurich\\Marcel_code\\new\\Att22R24\\output222\\";
		// to read time intervall file:
		timeIntervalReader tir = readTimeInterval(timeIntervalFileName,
				attFilePath, outputFilePath);
		Set<PrimLine> pls = new HashSet<PrimLine>();
		// to read input-.att-files and config min- and max departure time:
		for (int i = 0; i <= tir.getCnt(); i++) {
			pls.clear();
			String attTableFilename, outputFilename;
			Date minDepTime, maxDepTime;
			attTableFilename = tir.getInputFilename(i);
			try {
				minDepTime = sdf.parse(tir.getMinDepTime(i));
				maxDepTime = sdf.parse(tir.getMaxDepTime(i));

				outputFilename = tir.getOutputFilename(i);

				SecondFilter sf = new SecondFilter(";", attTableFilename,
						outputFilename);
				String line;
				// to search headline of the table
				do {
					line = sf.readLine();
					if (PutpathlegFilter.isHead(line))
						break;
					sf.writeLine(line);
				} while (line != null);
				sf.writeNewLine(line);// writes "$P...."
				// to read line of table
				// ...... PrimLine pl=null;
				do {
					line = sf.readLine();
					if (line != null)
						if (!line.startsWith(";")) {// the line is the first
							// line of an OD-zone
							String[] primLines = sf.split(line);
							if (primLines.length > 1) {
								Date depDate = sdf.parse(primLines[12]);
								if ((minDepTime.before(depDate) || minDepTime
										.equals(depDate))
										&& (depDate.before(maxDepTime) || depDate
												.equals(maxDepTime))) {

									PrimLine pl = new PrimLine(primLines[0],
											primLines[1], primLines[8],
											primLines[14]);
									if (pls.isEmpty()) {
										pls.add(pl);
										sf.writeNewLine(line);
									} else {
										boolean sameODpair = true;
										for (PrimLine plSaved : pls)
											if (!plSaved.sameODpair(pl)) {
												sameODpair = false;
												break;
											}
										if (sameODpair) {
											boolean samePl = false;
											for (PrimLine plSaved : pls)
												if (plSaved.samePrimLine(pl)) {
													samePl = true;
													break;
												}
											if (!samePl) {
												pls.add(pl);
												sf.writeNewLine(line);
											}
										} else {
											pls.clear();
											pls.add(pl);
											sf.writeNewLine(line);
										}
									}
								}
							}
						}
				} while (line != null);
				sf.closeReader();
				sf.closeWriter();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ParseException e) {
				e.printStackTrace();
			}
		}
	}
}
