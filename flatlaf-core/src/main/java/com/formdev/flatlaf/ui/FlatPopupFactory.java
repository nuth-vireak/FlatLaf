/*
 * Copyright 2020 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import com.formdev.flatlaf.util.SystemInfo;

/**
 * A popup factory that adds drop shadows to popups on Windows and Linux.
 * On macOS, heavy weight popups (without drop shadow) are produced and the
 * operating system automatically adds drop shadows.
 *
 * @author Karl Tauber
 */
public class FlatPopupFactory
	extends PopupFactory
{
	private Method java8getPopupMethod;
	private Method java9getPopupMethod;

	@Override
	public Popup getPopup( Component owner, Component contents, int x, int y )
		throws IllegalArgumentException
	{
		if( !UIManager.getBoolean( "Popup.dropShadowPainted" ) )
			return super.getPopup( owner, contents, x, y );

		// macOS and Linux adds drop shadow to heavy weight popups
		if( SystemInfo.IS_MAC || SystemInfo.IS_LINUX ) {
			Popup popup = getHeavyWeightPopup( owner, contents, x, y );
			if ( popup != null ) {
				// fix background flashing
				SwingUtilities.windowForComponent( contents ).setBackground( contents.getBackground() );
			}
			return (popup != null) ? popup : super.getPopup( owner, contents, x, y );
		}

		// create popup
		Popup popup = super.getPopup( owner, contents, x, y );

		// create drop shadow popup
		return new DropShadowPopup( popup, owner, contents );
	}

	/**
	 * There is no API in Java 8 to force creation of heavy weight popups,
	 * but it is possible with reflection. Java 9 provides a new method.
	 *
	 * When changing FlatLaf system requirements to Java 9+,
	 * then this method can be replaced with:
	 *    return getPopup( owner, contents, x, y, true );
	 */
	private Popup getHeavyWeightPopup( Component owner, Component contents, int x, int y )
		throws IllegalArgumentException
	{
		try {
			if( SystemInfo.IS_JAVA_9_OR_LATER ) {
				if( java9getPopupMethod == null ) {
					java9getPopupMethod = PopupFactory.class.getDeclaredMethod(
						"getPopup", Component.class, Component.class, int.class, int.class, boolean.class );
				}
				return (Popup) java9getPopupMethod.invoke( this, owner, contents, x, y, true );
			} else {
				// Java 8
				if( java8getPopupMethod == null ) {
					java8getPopupMethod = PopupFactory.class.getDeclaredMethod(
						"getPopup", Component.class, Component.class, int.class, int.class, int.class );
					java8getPopupMethod.setAccessible( true );
				}
				return (Popup) java8getPopupMethod.invoke( this, owner, contents, x, y, /*HEAVY_WEIGHT_POPUP*/ 2 );
			}
		} catch( NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException ex ) {
			// ignore
			return null;
		}
	}

	//---- class DropShadowPopup ----------------------------------------------

	private class DropShadowPopup
		extends Popup
	{
		private Popup delegate;

		// light weight
		private JComponent lightComp;
		private Border oldBorder;
		private boolean oldOpaque;

		// heavy weight
		private Window popupWindow;
		private Popup dropShadowDelegate;
		private Window dropShadowWindow;
		private Color oldBackground;

		DropShadowPopup( Popup delegate, Component owner, Component contents ) {
			this.delegate = delegate;

			// drop shadows on medium weight popups are not supported
			if( delegate.getClass().getName().endsWith( "MediumWeightPopup" ) )
				return;

			Dimension size = contents.getPreferredSize();
			if( size.width <= 0 || size.height <= 0 )
				return;

			popupWindow = SwingUtilities.windowForComponent( contents );
			if( popupWindow != null ) {
				// heavy weight popup

				// Since Java has a problem with sub-pixel text rendering on translucent
				// windows, we can not make the popup window translucent for the drop shadow.
				// (see https://bugs.openjdk.java.net/browse/JDK-8215980)
				// The solution is to create a second translucent window that paints
				// the drop shadow and is positioned behind the popup window.

				// create panel that paints the drop shadow
				JPanel dropShadowPanel = new JPanel();
				dropShadowPanel.setBorder( createDropShadowBorder() );
				dropShadowPanel.setOpaque( false );

				// set preferred size of drop shadow panel
				Dimension prefSize = popupWindow.getPreferredSize();
				Insets insets = dropShadowPanel.getInsets();
				dropShadowPanel.setPreferredSize( new Dimension(
					prefSize.width + insets.left + insets.right,
					prefSize.height + insets.top + insets.bottom ) );

				// create popup for drop shadow
				int x = popupWindow.getX() - insets.left;
				int y = popupWindow.getY() - insets.top;
				dropShadowDelegate = getHeavyWeightPopup( owner, dropShadowPanel, x, y );

				// make drop shadow popup translucent
				dropShadowWindow = SwingUtilities.windowForComponent( dropShadowPanel );
				if( dropShadowWindow != null ) {
					oldBackground = dropShadowWindow.getBackground();
					dropShadowWindow.setBackground( new Color( 0, true ) );
				}
			} else {
				// light weight popup
				Container p = contents.getParent();
				if( !(p instanceof JComponent) )
					return;

				lightComp = (JComponent) p;
				oldBorder = lightComp.getBorder();
				oldOpaque = lightComp.isOpaque();
				lightComp.setBorder( createDropShadowBorder() );
				lightComp.setOpaque( false );
				lightComp.setSize( lightComp.getPreferredSize() );
			}
		}

		private Border createDropShadowBorder() {
			return new FlatDropShadowBorder(
				UIManager.getColor( "Popup.dropShadowColor" ),
				UIManager.getInsets( "Popup.dropShadowInsets" ),
				FlatUIUtils.getUIFloat( "Popup.dropShadowOpacity", 0.5f ) );
		}

		@Override
		public void show() {
			if( dropShadowDelegate != null )
				dropShadowDelegate.show();

			delegate.show();
		}

		@Override
		public void hide() {
			if( dropShadowDelegate != null ) {
				dropShadowDelegate.hide();
				dropShadowDelegate = null;
			}

			if( delegate != null ) {
				delegate.hide();
				delegate = null;
			}

			if( dropShadowWindow != null ) {
				dropShadowWindow.setBackground( oldBackground );
				dropShadowWindow = null;
			}

			if( lightComp != null ) {
				lightComp.setBorder( oldBorder );
				lightComp.setOpaque( oldOpaque );
				lightComp = null;
			}
		}
	}
}
