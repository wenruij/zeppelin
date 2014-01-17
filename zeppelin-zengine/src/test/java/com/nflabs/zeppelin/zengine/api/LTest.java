package com.nflabs.zeppelin.zengine.api;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.jointhegrid.hive_test.HiveTestBase;
import com.jointhegrid.hive_test.HiveTestService;
import com.nflabs.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import com.nflabs.zeppelin.driver.hive.HiveZeppelinDriver;
import com.nflabs.zeppelin.result.Result;
import com.nflabs.zeppelin.util.UtilsForTests;
import com.nflabs.zeppelin.zengine.ZException;

public class LTest extends HiveTestService {
    
    private File tmp;
	private String tmpDir;
    private String tmpUri;

    @Rule
    public ExpectedException thrown= ExpectedException.none();
    
    public LTest() throws IOException {
        super();
    }

    @Before
	public void setUp() throws Exception {
		super.setUp();
		tmp = new File(System.getProperty("java.io.tmpdir")+"/ZeppelinLTest_"+System.currentTimeMillis());		
		tmp.mkdir();
		tmpDir = tmp.getAbsolutePath();
		tmpUri = tmp.toURI().toString();

		UtilsForTests.delete(new File("/tmp/warehouse"));
		UtilsForTests.delete(new File(ROOT_DIR.getName()));
		
		System.setProperty(ConfVars.ZEPPELIN_ZAN_LOCAL_REPO.getVarName(), tmpUri );
		//Dependensies: ZeppelinDriver + ZeppelinConfiguration
		Z.configure();
		
		HiveZeppelinDriver driver = new HiveZeppelinDriver(Z.getConf());
		driver.setClient(client);
		
		Z.setDriver(driver);
	}

    @After
	public void tearDown() throws Exception {
		UtilsForTests.delete(tmp);
		super.tearDown();
		
		UtilsForTests.delete(new File("/tmp/warehouse"));
		UtilsForTests.delete(new File(ROOT_DIR.getName()));
	}
    

	@Test
	public void testLoadingNonExistentLibrary() throws IOException, ZException {
		generateTestLibraryIn(tmpDir);
		
		thrown.expect(ZException.class);
		// load nonexisting L
		try {
		    new L("abc");
		} catch (ZException e) {
		    assertTrue(e.getMessage().contains("does not exist"));
		}
	}

	/**
	 * Generates the mock of Zeppelin Library in file system
	 * @param path of the library root
	 * @throws IOException
	 */
    private void generateTestLibraryIn(String path) throws IOException {
        File f = new File(path+"/test");
        if (!f.exists()) { f.mkdir(); }
        
        String zqlQuery = "CREATE VIEW <%= z." + Q.OUTPUT_VAR_NAME + " %> AS select * from table limit <%= z.param('limit') %>\n";
        
        createFileWithContent(path+"/test/zql.erb", zqlQuery);
        // create resource that will be ignored
		createFileWithContent(path+"/test/no_resource", "");
        // create resource
        createFileWithContent(path+"/test/test_data.log", "");
    }

    /**
     * Utility method to create a file (if does not exist) and populate it the the given content
     * 
     * @param path to file
     * @param content of the file
     * @throws IOException
     */
    private void createFileWithContent(String path, String content) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            FileOutputStream out = new FileOutputStream(f);
            out.write((content).getBytes());
            out.close();
        }
    }

	
	@Test
	public void testLoadingExistingLibrary() throws ZException, IOException {
	    generateTestLibraryIn(tmpDir);

        // load existing L
        L test = new L("test");
        test.withParam("limit", 3);
        test.withName("hello");
        assertEquals("CREATE VIEW "+test.name()+" AS select * from table limit 3", test.getQuery());
        List<URI> res = test.getResources();
        assertEquals(1, res.size());
        assertEquals("file://"+tmpDir+"/test/test_data.log", res.get(0).toString());
        test.release();
	}

	public void testWeb() throws Exception{
		new File(tmpDir+"/test/web").mkdirs();

		createFileWithContent(tmpDir+"/test/zql.erb", "show tables");
        createFileWithContent(tmpDir+"/test/web/index.erb", "HELLO HTML\n");

		// load existing L
		Z test = new L("test");//.execute();
		InputStream ins = test.readWebResource("/");
		assertEquals("HELLO HTML", IOUtils.toString(ins, "utf8"));
	}
	
	public void testWebOnlyLibrary() throws IOException, ZException{
		new File(tmpDir+"/test/web").mkdirs();

		Path p = new Path(HiveTestBase.ROOT_DIR, "afile");

	    FSDataOutputStream o = this.getFileSystem().create(p);
	    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(o));
	    bw.write("5\n");
	    bw.write("2\n");
	    bw.close();

		new Q("drop table if exists test").execute().result().write(System.out);
		new Q("create table test(a INT)").execute().result().write(System.out);
		new Q("load data local inpath '" + p.toString() + "' into table test").execute().result().write(System.out);

		File erb = new File(tmpDir+"/test/web/index.erb");
		FileOutputStream out = new FileOutputStream(erb);		
		out.write("HELLO HTML <%= z.result.rows[0][0] %>\n".getBytes());
		out.close();
		
		Z z = new Q("select * from test").pipe(new L("test"));
		Result result = z.execute().result();
		assertEquals(5, result.getRows().get(0)[0]);
		
		InputStream ins = z.readWebResource("/");
		assertEquals("HELLO HTML 5", IOUtils.toString(ins, "utf8"));
	}
	
	public void testWebOnlyLibraryPipe() throws IOException, ZException{
		new File(tmpDir+"/test/web").mkdirs();

		Path p = new Path(HiveTestBase.ROOT_DIR, "afile");

	    FSDataOutputStream o = this.getFileSystem().create(p);
	    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(o));
	    bw.write("5\n");
	    bw.write("2\n");
	    bw.close();
	    
		new Q("drop table if exists test").execute().result().write(System.out);
		new Q("create table test(a INT)").execute().result().write(System.out);
		new Q("load data local inpath '" + p.toString() + "' into table test").execute().result().write(System.out);

		File erb = new File(tmpDir+"/test/web/index.erb");
		FileOutputStream out = new FileOutputStream(erb);		
		out.write("HELLO HTML <%= z.result.rows[0][0] %>\n".getBytes());
		out.close();
		
		Z z = new Q("select * from test").pipe(new L("test")).pipe(new L("test"));
		Result result = z.execute().result();
		assertEquals(5, result.getRows().get(0)[0]);
	}

}
