/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.datatype;

import java.awt.Component;
import java.util.*;

import javax.swing.*;

import docking.widgets.DropDownSelectionTextField;
import docking.widgets.DropDownTextFieldDataModel;
import docking.widgets.list.GListCellRenderer;
import ghidra.app.plugin.core.compositeeditor.CompositeViewerDataTypeManager;
import ghidra.app.plugin.core.datamgr.util.DataTypeUtils;
import ghidra.app.services.DataTypeManagerService;
import ghidra.app.util.ToolTipUtils;
import ghidra.framework.plugintool.ServiceProvider;
import ghidra.program.database.data.DataTypeUtilities;
import ghidra.program.model.data.*;
import ghidra.util.UniversalID;
import ghidra.util.exception.AssertException;

/**
 * The data model for {@link DropDownSelectionTextField} that allows the text field to work with
 * {@link DataType}s.
 */
public class DataTypeDropDownSelectionDataModel implements DropDownTextFieldDataModel<DataType> {

	private final DataTypeManager dtm;	// preferred data type manager
	private final DataTypeManagerService dataTypeService;

	public DataTypeDropDownSelectionDataModel(ServiceProvider serviceProvider) {
		this.dtm = null;
		this.dataTypeService = getDataTypeService(serviceProvider);
	}

	/**
	 * Creates a new instance.
	 * 
	 * @param dtm the preferred {@link DataTypeManager}.  Data types that are found in multiple 
	 * data type managers will be pruned to just the ones already in the preferred data type 
	 * manager.
	 * @param dataTypeService {@link DataTypeManagerService}
	 */
	public DataTypeDropDownSelectionDataModel(DataTypeManager dtm,
			DataTypeManagerService dataTypeService) {
		this.dtm = dtm;
		this.dataTypeService = dataTypeService;
	}

	private DataTypeManagerService getDataTypeService(ServiceProvider serviceProvider) {
		DataTypeManagerService service = serviceProvider.getService(DataTypeManagerService.class);
		if (service == null) {
			throw new AssertException("Unable to find required DataTypeManagerService.");
		}
		return service;
	}

	@Override
	public ListCellRenderer<DataType> getListRenderer() {
		return new DataTypeDropDownRenderer();
	}

	@Override
	public String getDescription(DataType value) {
		return ToolTipUtils.getToolTipText(value);
	}

	@Override
	public String getDisplayText(DataType value) {
		return value.getName();
	}

	@Override
	public List<DataType> getMatchingData(String searchText) {
		if (searchText == null || searchText.length() == 0) {
			return Collections.emptyList();
		}

		List<DataType> dataTypeList =
			DataTypeUtils.getStartsWithMatchingDataTypes(searchText, dataTypeService);
		return filterDataTypeList(dataTypeList);
	}

	/**
	 * Remove any unwanted data type items, like arrays.
	 */
	private List<DataType> filterDataTypeList(List<DataType> dataTypeList) {
		// build lookups for data types that are in the preferred dtm, but may have come from
		// another DTM.  In the second step, duplicate data types will be omitted from the
		// final results, in favor of the data type that is already in the preferred dtm.
		Set<UniversalID> preferredUIDs = new HashSet<>();
		Set<Class<?>> preferredBuiltins = new HashSet<>();
		for (DataType dt : dataTypeList) {
			DataType baseDT = DataTypeUtilities.getBaseDataType(dt);
			if (isFromPreferredDTM(baseDT)) {
				if (baseDT instanceof BuiltInDataType) {
					preferredBuiltins.add(baseDT.getClass());
				}
				else if (baseDT.getUniversalID() != null) {
					preferredUIDs.add(baseDT.getUniversalID());
				}
			}
		}

		List<DataType> matchingList = new ArrayList<>(dataTypeList.size());
		for (DataType dataType : dataTypeList) {
			if (dataType instanceof Array) {
				continue;
			}
			DataType baseDT = DataTypeUtilities.getBaseDataType(dataType);
			if (dtm != null && !isFromPreferredDTM(baseDT)) {
				if (baseDT instanceof BuiltInDataType &&
					preferredBuiltins.contains(baseDT.getClass())) {
					continue;
				}
				if (baseDT.getUniversalID() != null &&
					preferredUIDs.contains(baseDT.getUniversalID())) {
					continue;
				}
			}

			matchingList.add(dataType);
		}

		return matchingList;
	}

	private boolean isFromPreferredDTM(DataType dt) {
		if (dtm != null) {
			DataTypeManager altDTM = dtm instanceof CompositeViewerDataTypeManager compDTM
					? compDTM.getOriginalDataTypeManager()
					: null;
			DataTypeManager dtDTM = dt.getDataTypeManager();
			return dtDTM == dtm || dtDTM == altDTM;
		}
		return false;
	}

	@Override
	public int getIndexOfFirstMatchingEntry(List<DataType> data, String text) {

		text = DataTypeUtils.prepareSearchText(text);

		// The data are sorted such that lower-case is before upper-case and smaller length 
		// matches come before longer matches.  If we ever find a case-sensitive exact match, 
		// use that. Otherwise, keep looking for a case-insensitive exact match.  The 
		// case-insensitive match is preferred over a non-matching item.  Once we get to a 
		// non-matching item, we can quit.
		int lastPreferredMatchIndex = -1;
		for (int i = 0; i < data.size(); i++) {
			DataType dataType = data.get(i);
			String dataTypeName = dataType.getName();
			dataTypeName = dataTypeName.replaceAll(" ", "");
			if (dataTypeName.equals(text)) {
				// an exact match is the best possible match!
				return i;
			}

			if (dataTypeName.equalsIgnoreCase(text)) {
				// keep going, but remember this location, in case we don't find any more matches
				lastPreferredMatchIndex = i;
			}
			else {
				// we've encountered a non-matching entry--nothing left to search
				return lastPreferredMatchIndex;
			}
		}

		return -1; // we only get here when the list is empty
	}

	private class DataTypeDropDownRenderer extends GListCellRenderer<DataType> {

		@Override
		protected String getItemText(DataType dt) {
			DataTypeManager dtm = dt.getDataTypeManager();
			String dtmName = (dtm != null) ? dtm.getName() : "";
			return dt.getName() + " - " + dtmName + dt.getPathName();
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends DataType> list,
				DataType value, int index, boolean isSelected, boolean cellHasFocus) {

			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			setIcon(DataTypeUtils.getIconForDataType(value, false));
			setVerticalAlignment(SwingConstants.TOP);

			return this;
		}
	}

}
