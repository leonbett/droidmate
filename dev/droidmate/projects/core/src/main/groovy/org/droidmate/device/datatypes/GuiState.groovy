// Copyright (c) 2012-2016 Saarland University
// All rights reserved.
//
// Author: Konrad Jamrozik, jamrozik@st.cs.uni-saarland.de
//
// This file is part of the "DroidMate" project.
//
// www.droidmate.org

package org.droidmate.device.datatypes

import groovy.transform.Canonical
import org.droidmate.common.TextUtilsCategory
import org.droidmate.common.exploration.datatypes.Widget
import org.droidmate.configuration.device.IDeviceSpecificConfiguration

// WISH Borges this class has to be adapted to work with other devices, e.g. Samsung Galaxy S III
// DroidMate should ask uiautomator-daemon for the the device model
// see http://stackoverflow.com/questions/6579968/how-can-i-get-the-device-name-in-android)
// see http://stackoverflow.com/questions/1995439/get-android-phone-model-programmatically
// Probably the data should be obtained in a similar manner as in org.droidmate.device.MonitorsClient.isServerReachable
// but instead the uiautomator-daemon should be asked, and the call probably should be made during
// org.droidmate.tools.AndroidDeviceDeployer.trySetUp to then keep the obtained info inside the RobustDevice instance.
//
// Note that isHomeScreen is already adapted.
@Canonical(excludes = "id")
class GuiState implements Serializable, IGuiState
{

  private static final long serialVersionUID = 1

  private static final String package_android                            = "android"

  private final IDeviceSpecificConfiguration deviceConfiguration


  final String       topNodePackageName
  final List<Widget> widgets

  /** Id is used only for tests, for easy determination by human which instance is which when looking at widget string
   * representation. */
  final String id

  GuiState(String topNodePackageName, List<Widget> widgets, IDeviceSpecificConfiguration deviceConfiguration)
  {
    this(topNodePackageName, null, widgets, deviceConfiguration)
  }

  GuiState(String topNodePackageName, String id, List<Widget> widgets, IDeviceSpecificConfiguration deviceConfiguration)
  {
    this.topNodePackageName = topNodePackageName
    this.widgets = widgets
    this.id = id
    this.deviceConfiguration = deviceConfiguration

    assert this.deviceConfiguration != null
    assert !this.topNodePackageName?.empty
    assert widgets != null
  }

  GuiState(IGuiState guiState, String id, IDeviceSpecificConfiguration deviceConfiguration)
  {
    this.topNodePackageName = guiState.topNodePackageName
    this.widgets = guiState.widgets
    this.id = id
    this.deviceConfiguration = deviceConfiguration
  }


  @Override
  public List<Widget> getActionableWidgets()
  {
    widgets.findAll {it.canBeActedUpon()}
  }

  @Override
  public String toString()
  {
    use(TextUtilsCategory) {
      if (this.isHomeScreen())
        return "GUI state: home screen".wrapWith("<>")

      if (this instanceof AppHasStoppedDialogBoxGuiState)
        return "GUI state of \"App has stopped\" dialog box. OK widget enabled: ${(this as AppHasStoppedDialogBoxGuiState).OKWidget.enabled}".wrapWith("<>")

      return "GuiState " + (id != null ? "id=$id " : "") + "pkg=$topNodePackageName Widgets count = ${widgets.size()}".wrapWith("<>")
    }
  }

  @Override
  boolean isHomeScreen()
  {
    return this.deviceConfiguration.isHomeScreen(this)
  }

  @Override
  boolean isAppHasStoppedDialogBox()
  {
    return topNodePackageName == package_android &&
      widgets.any {it.text == "OK"} &&
      !widgets.any {it.text == "Just once"}
  }

  @Override
  boolean isCompleteActionUsingDialogBox()
  {
    return !isSelectAHomeAppDialogBox() && topNodePackageName == package_android &&
      widgets.any {it.text == "Just once"}
  }

  @Override
  boolean isSelectAHomeAppDialogBox()
  {
    return topNodePackageName == package_android &&
      widgets.any {it.text == "Just once"} && widgets.any {it.text == "Select a home app"}
  }


  @Override
  boolean belongsToApp(String appPackageName)
  {
    return this.topNodePackageName == appPackageName
  }

}
