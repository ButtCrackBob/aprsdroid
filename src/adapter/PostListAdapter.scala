package org.aprsdroid.app

import _root_.android.database.Cursor
import _root_.android.content.Context
import _root_.android.graphics.Typeface
import _root_.android.view.View
import _root_.android.widget.SimpleCursorAdapter
import _root_.android.widget.SimpleCursorAdapter.ViewBinder
import _root_.android.widget.TextView

object PostListAdapter {
	val LIST_FROM = Array("TSS", StorageDatabase.Post.STATUS,
		StorageDatabase.Post.MESSAGE)
	val LIST_TO = Array(R.id.listts, R.id.liststatus, R.id.listmessage)
}

class PostListAdapter(context : Context)
		extends SimpleCursorAdapter(context, R.layout.listitem,
			null, PostListAdapter.LIST_FROM, PostListAdapter.LIST_TO) {

	setViewBinder(new PostViewBinder())
}


class PostViewBinder extends ViewBinder {

	// post, info, error
	val COLORS = Array(0xff30b030, 0xffb0b0b0, 0xffffb0b0, 0xff8080b0)

	override def setViewValue (view : View, cursor : Cursor, columnIndex : Int) : Boolean = {
		import StorageDatabase.Post._
		columnIndex match {
		case COLUMN_MESSAGE =>
			val t = cursor.getInt(COLUMN_TYPE)
			val m = cursor.getString(COLUMN_MESSAGE)
			val v = view.asInstanceOf[TextView]
			v.setText(m)
			v.setTextColor(COLORS(t))
			if (t == TYPE_POST || t == TYPE_INCMG)
				v.setTypeface(Typeface.MONOSPACE)
			else
				v.setTypeface(Typeface.DEFAULT)

			true
		case _ => false
	}
	}
}
