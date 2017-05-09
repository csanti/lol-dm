package org.cities.datacleaning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;

/**
 * Created by Carlos on 30/04/2017.
 */
public class JsonFileCleaning {
    private static final String API_KEY = "6ab6acff-85f6-4580-b127-32067e5667ab";
    private static final String BASE_URL = "https://euw1.api.riotgames.com/api/lol/euw/";
    private static int requests = 0;

    public static void main(String[] args) {
        if( args.length != 2 ){
            usage();
        }

        String file_name = args[0];
        String out_file_name = args[1];
        PrintWriter writer;
        try {
            writer = new PrintWriter(new File(out_file_name));
            writer.println("Team1Win,Champ1WR,Champ2WR,Champ3WR,Champ4WR,Champ5WR,Champ6WR,Champ7WR,Champ8WR,Champ9WR,Champ10WR,PlayerChamp1WR,PlayerChamp2WR,PlayerChamp3WR,PlayerChamp4WR,PlayerChamp5WR,PlayerChamp6WR,PlayerChamp7WR,PlayerChamp8WR,PlayerChamp9WR,PlayerChamp10WR,Player1ChampTimes,Player2ChampTimes,Player3ChampTimes,Player4ChampTimes,Player5ChampTimes,Player6ChampTimes,Player7ChampTimes,Player8ChampTimes,Player9ChampTimes,Player10ChampTimes,Player1Ranking,Player2Ranking,Player3Ranking,Player4Ranking,Player5Ranking,Player6Ranking,Player7Ranking,Player8Ranking,Player9Ranking,Player10Ranking");
        } catch (Exception e) {
            System.out.println("Error creating printwriter");
            return;
        }


        //go through each file
        String json_string = "";
        String champion_json_string = "";
        try{
            List<String> lines = Files.readAllLines( Paths.get( file_name ), Charset.forName("ISO-8859-1") );
            ListIterator<String> lines_iterator = lines.listIterator();
            while( lines_iterator.hasNext() ){
                json_string += lines_iterator.next();
            }

            List<String> champ_lines = Files.readAllLines( Paths.get( "ChampionIdWinRates.json" ), Charset.forName( "UTF-8" ) );
            ListIterator<String> champ_lines_iterator = champ_lines.listIterator();
            while( champ_lines_iterator.hasNext() ){
                champion_json_string += champ_lines_iterator.next();
            }

        }catch( IOException e ){
            System.err.println( e );
        }

        JSONObject champ_wr = new JSONObject( champion_json_string );
        JSONArray matches = new JSONObject( json_string ).getJSONArray( "matches" );

        List<Long> cleanedMatchesIds = new ArrayList<Long>();
        int skippedMatchesCount = 0;

        for(int i = 0; i < matches.length(); i++) {
            /* Output parameters */
            boolean Team1Win = false;
            double[] champWr = new double[10];
            double[] playerChampWr = new double[10];
            double[] playerRecentWr = new double[10];
            int[] playerChampTimesPlayed = new int[10];
            int[] playerRanking = new int[10];

            JSONObject match = matches.getJSONObject(i);
            long match_id = match.getLong( "matchId" );
            if(cleanedMatchesIds.contains(match_id)) {
                skippedMatchesCount++;
                continue;
            }

            JSONArray teams = match.getJSONArray("teams");

            /* Team1Win */

            if(teams.getJSONObject(0).getBoolean("winner") == true) {
                Team1Win = true;
            }

            /* playerChampWr */

            String participantSummonerIds = "";

            JSONArray participants = match.getJSONArray("participants");
            JSONArray participantsIdentities = match.getJSONArray("participantIdentities");
            for(int j = 0; j < participantsIdentities.length(); j++) {

                // me gio por el sumoner id y participant id de la identidad
                long summonerId = participantsIdentities.getJSONObject(j).getJSONObject("player").getLong("summonerId");
                int participantId = participantsIdentities.getJSONObject(j).getInt("participantId");

                // creo la lista de summoners ids para luego hacer la peticion de ligas
                if(j == participants.length() - 1)
                    participantSummonerIds += summonerId;
                else
                    participantSummonerIds += summonerId+",";

                // busco el participantid en la lista y saco el champion que juega
                int participantChampionId = 0;
                for(int j2 = 0; j2 < participants.length(); j2++) {
                    if(participants.getJSONObject(j2).getInt("participantId") == participantId) {
                        participantChampionId = participants.getJSONObject(j2).getInt("championId");
                        break;
                    }
                }
                // Extraigo de la lista de winrates de cahmpions estatica, el wr del  champion que esta jugando
                champWr[participantId-1] = champ_wr.getDouble(Integer.toString(participantChampionId));

                // hago la peticion al endpoint de stats para ver el wr y partidas jugadas con ese champ
                JSONObject participant_stats_by_champ = executeGetRequest(BASE_URL+"v1.3/stats/by-summoner/"+summonerId+"/ranked?season=SEASON2017&api_key="+API_KEY);
                JSONArray particpant_stats_champ_list = participant_stats_by_champ.getJSONArray("champions");

                // busco el champion que esta jugando en esta partida dentro de la lista de todos lo que ha jugado
                for(int c = 0; c < particpant_stats_champ_list.length(); c++) {

                    if(particpant_stats_champ_list.getJSONObject(c).getInt("id") == participantChampionId) {

                        JSONObject champ_stats = particpant_stats_champ_list.getJSONObject(c).getJSONObject("stats");

                        playerChampTimesPlayed[participantId-1] = champ_stats.getInt("totalSessionsPlayed");
                        playerChampWr[participantId-1] = (double)champ_stats.getInt("totalSessionsWon") / (double)champ_stats.getInt("totalSessionsPlayed");

                        break;
                    }
                }
            }

            JSONObject participantsLeagueInfo = executeGetRequest(BASE_URL+"v2.5/league/by-summoner/"+participantSummonerIds+"/entry?api_key="+API_KEY);

            // Vuelvo a recorrer la lista de participantes para encontrar la parte que le corresponde en la respuesta conjunta de las ligas
            for(int p = 0; p < participantsIdentities.length(); p++) {
                long summonerId = participantsIdentities.getJSONObject(p).getJSONObject("player").getLong("summonerId");
                int participantId = participantsIdentities.getJSONObject(p).getInt("participantId");
                if(participantsLeagueInfo.has(Long.toString(summonerId))) {
                    // Recorro las diferentes ligas que tiene el jguador
                    JSONArray leagues = participantsLeagueInfo.getJSONArray(Long.toString(summonerId));
                    for(int l = 0; l < leagues.length(); l++) {
                        if(leagues.getJSONObject(l).getString("queue").equals("RANKED_SOLO_5x5")) {
                            JSONObject pLeagueInfo = leagues.getJSONObject(l);
                            playerRanking[participantId-1] = getTierMapping(pLeagueInfo.getString("tier")) + getDivisionMapping(pLeagueInfo.getJSONArray("entries").getJSONObject(0).getString("division"));
                            break;
                        }
                    }
                }
                else {
                    System.err.println("League information not found for participant number "+participantId);
                }
            }

            System.out.println("Match "+ i +" procesado, guardando...");
            // Match procesado, procedemos a guardar
            try {
                if(Team1Win) {
                    writer.print("1,");
                } else {
                    writer.print("0,");
                }
                for(int in = 0; in < champWr.length; in++) {
                    writer.print(champWr[in]+",");
                }
                for(int in = 0; in < playerChampWr.length; in++) {
                    writer.print(playerChampWr[in]+",");
                }
                for(int in = 0; in < playerChampTimesPlayed.length; in++) {
                    writer.print(playerChampTimesPlayed[in]+",");
                }
                for(int in = 0; in < playerRanking.length; in++) {
                    if(in == 9) {
                        writer.print(playerRanking[in]+"\n");
                        break;
                    }
                    writer.print(playerRanking[in]+",");
                }
                cleanedMatchesIds.add(match_id);
            } catch(Exception e) {
                System.err.println("Error writing match results "+ e.toString());
            }
        }
        System.out.println("Limpiados: "+cleanedMatchesIds.size()+" Skipped: "+skippedMatchesCount);
        System.out.println("Cerrando writer...");
        writer.close();

    }

    public static int getDivisionMapping( String division ){
        switch( division ){
            case "I":
                return 5;
            case "II":
                return 4;
            case "III":
                return 3;
            case "IV":
                return 2;
            case "V":
                return 1;
            default:
                return 0;
        }
    }

    public static int getTierMapping( String tier ){
        switch( tier ){
            case "BRONZE":
                return 0;
            case "SILVER":
                return 6;
            case "GOLD":
                return 12;
            case "PLATINUM":
                return 18;
            case "DIAMOND":
                return 24;
            case "MASTER":
                return 30;
            case "CHALLENGER":
                return 36;
        }
        return 8;
    }


    public static JSONObject executeGetRequest( String url){
        try{
            /*
            requests += 1;
            if( requests % 500 == 0 ){

                TimeUnit.MINUTES.sleep(10);
            } */
            TimeUnit.SECONDS.sleep(1);
            URL mh_url = new URL( url );
            System.out.println( url );
            HttpURLConnection con = (HttpURLConnection) mh_url.openConnection();
            con.setConnectTimeout(5000); // si tarda mas de 5 segundos lanza SocketTimeoutException
            int responseCode = con.getResponseCode();
            if( responseCode != 200 ){
                /*
                if(responseCode == 404) {
                    System.err.println("Response Code " + responseCode + " received.");
                    return null;
                }
                */

                System.err.println( "Response Code " + responseCode + " received. Re issuing request." );
                TimeUnit.SECONDS.sleep( 10 );
                return executeGetRequest( url);
            }
            BufferedReader in = new BufferedReader( new InputStreamReader( con.getInputStream() ) );
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();
            return new JSONObject( response.toString() );

        }catch(SocketTimeoutException e) {
            System.out.print("Request timeout, reintentando...");
            return executeGetRequest(url); // si hay un timeout se vuelve a intentar
        }catch( Exception e ){
            System.err.println( e );
            return null;
        }
    }

    public static void usage(){
        System.err.println( "Usage: JsonFileCleaning <input_file_name.json> <output_file_name.csv>");
    }


}
